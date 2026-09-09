package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope, readerPreferences)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private val threshold: Int by lazy { readerPreferences.readerHideThreshold.get().threshold }

    private val screenHeight = activity.resources.displayMetrics.heightPixels

    /**
     * Pixels per second scrolled while a scroll key is held and [WebtoonConfig.smoothKeyScroll] is
     * on. Read per frame so slider changes apply without reopening the reader.
     */
    private val keyScrollVelocity: Float
        get() = screenHeight * config.smoothKeyScrollSpeed / SCREEN_FRACTION_DENOMINATOR

    /**
     * Direction of the active hold-to-scroll: -1 up, 1 down, 0 idle.
     */
    private var holdScrollDirection = 0

    /**
     * Whether auto-scroll is currently running. Held scroll keys temporarily override it, and it
     * resumes when they are released.
     */
    private var autoScrollActive = false

    /**
     * When auto-scroll was last engaged, used to ease it in. Not reset when a held key
     * temporarily overrides it, so releasing the key resumes at full speed.
     */
    private var autoScrollStartMillis = 0L

    private var scrollLoopRunning = false
    private var scrollLoopLastFrameNanos = 0L

    /**
     * Consecutive quick presses of volume up, used to detect the triple press that toggles
     * auto-scroll. Reset whenever the gap between presses exceeds [MULTI_PRESS_WINDOW_MILLIS].
     */
    private var volumeUpPressCount = 0
    private var volumeUpLastPressMillis = 0L

    /**
     * Sub-pixel scroll carried over between frames so slow speeds don't round to zero.
     */
    private var scrollRemainder = 0f

    /**
     * Whether anything currently wants the frame loop running. Kept separate from
     * [activeScrollVelocity] because that is momentarily zero while auto-scroll eases in.
     */
    private val scrollLoopWanted: Boolean
        get() = holdScrollDirection != 0 || autoScrollActive

    /**
     * Fraction of the target auto-scroll speed to apply, easing linearly from a standstill over
     * [AUTO_SCROLL_RAMP_MILLIS] so engaging it glides rather than lurches.
     */
    private val autoScrollRampFactor: Float
        get() {
            val elapsed = SystemClock.uptimeMillis() - autoScrollStartMillis
            return (elapsed.toFloat() / AUTO_SCROLL_RAMP_MILLIS).coerceIn(0f, 1f)
        }

    /**
     * Signed pixels per second the frame loop should currently scroll by, or zero when idle.
     * A held key wins over auto-scroll so manual navigation stays responsive.
     */
    private val activeScrollVelocity: Float
        get() = when {
            holdScrollDirection != 0 -> holdScrollDirection * keyScrollVelocity
            autoScrollActive ->
                screenHeight * config.autoScrollSpeed / SCREEN_FRACTION_DENOMINATOR * autoScrollRampFactor
            else -> 0f
        }

    private val scrollFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scrollLoopWanted) {
                stopScrollLoop()
                return
            }
            val velocity = activeScrollVelocity
            if (scrollLoopLastFrameNanos != 0L) {
                val dtSeconds = ((frameTimeNanos - scrollLoopLastFrameNanos) / NANOS_PER_SECOND)
                    .coerceAtMost(SCROLL_MAX_FRAME_SECONDS)
                val dy = velocity * dtSeconds + scrollRemainder
                val wholeDy = dy.toInt()
                scrollRemainder = dy - wholeDy
                if (wholeDy != 0) recycler.scrollBy(0, wholeDy)
            }
            // Stop auto-scrolling once the end of the loaded content is reached, so the frame loop
            // doesn't spin forever against a recycler that can no longer move.
            if (autoScrollActive && holdScrollDirection == 0 && !recycler.canScrollVertically(1)) {
                setAutoScroll(false)
                return
            }
            scrollLoopLastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            // Never keep advancing the chapter while the reader is in the background.
            setAutoScroll(false)
            stopHoldScroll()
        }
    }

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        config.autoScrollKeyToggleChangedListener = { enabled ->
            if (!enabled) setAutoScroll(false)
        }

        // Any touch on the pages cancels auto-scroll, which covers drags, flings and tap-zone
        // navigation alike. The listener only observes; it never consumes the event.
        recycler.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) setAutoScroll(false)
                    return false
                }
            },
        )

        activity.lifecycle.addObserver(lifecycleObserver)

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        activity.lifecycle.removeObserver(lifecycleObserver)
        autoScrollActive = false
        holdScrollDirection = 0
        stopScrollLoop()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Starts the per-frame scroll loop if anything currently wants to scroll, and stops it
     * otherwise. Safe to call whenever hold or auto-scroll state changes.
     */
    private fun updateScrollLoop() {
        if (scrollLoopWanted) {
            if (scrollLoopRunning) return
            scrollLoopRunning = true
            scrollLoopLastFrameNanos = 0L
            scrollRemainder = 0f
            Choreographer.getInstance().postFrameCallback(scrollFrameCallback)
        } else {
            stopScrollLoop()
        }
    }

    private fun stopScrollLoop() {
        if (!scrollLoopRunning) return
        scrollLoopRunning = false
        scrollLoopLastFrameNanos = 0L
        scrollRemainder = 0f
        Choreographer.getInstance().removeFrameCallback(scrollFrameCallback)
    }

    /**
     * Scrolls continuously in [direction] (-1 up, 1 down) until [stopHoldScroll] is called.
     */
    private fun startHoldScroll(direction: Int) {
        if (holdScrollDirection == direction) return
        recycler.stopScroll()
        holdScrollDirection = direction
        scrollLoopLastFrameNanos = 0L
        scrollRemainder = 0f
        updateScrollLoop()
    }

    /**
     * Ends a held scroll. Auto-scroll, if it was running underneath, resumes on the next frame.
     */
    private fun stopHoldScroll() {
        if (holdScrollDirection == 0) return
        holdScrollDirection = 0
        scrollLoopLastFrameNanos = 0L
        scrollRemainder = 0f
        updateScrollLoop()
    }

    /**
     * Counts a volume up release and toggles auto-scroll on the third in quick succession. The
     * presses still scroll as usual; suppressing them would either add latency to every single
     * press or break rapid tapping as a way to page through a chapter.
     */
    private fun handleVolumeUpMultiPress(event: KeyEvent) {
        val now = event.eventTime
        volumeUpPressCount = if (now - volumeUpLastPressMillis <= MULTI_PRESS_WINDOW_MILLIS) {
            volumeUpPressCount + 1
        } else {
            1
        }
        volumeUpLastPressMillis = now
        if (volumeUpPressCount >= VOLUME_PRESSES_TO_TOGGLE) {
            volumeUpPressCount = 0
            setAutoScroll(!autoScrollActive)
        }
    }

    private fun setAutoScroll(enabled: Boolean) {
        if (autoScrollActive == enabled) return
        autoScrollActive = enabled
        if (enabled) {
            autoScrollStartMillis = SystemClock.uptimeMillis()
            recycler.stopScroll()
        }
        scrollLoopLastFrameNanos = 0L
        scrollRemainder = 0f
        updateScrollLoop()
    }

    /**
     * Handles a scroll key [event]. With [WebtoonConfig.smoothKeyScroll] enabled the viewer scrolls
     * continuously from ACTION_DOWN until ACTION_UP; otherwise the original single jump fires on
     * ACTION_UP. [forward] is true for keys that scroll towards the end of the chapter.
     */
    private fun handleScrollKey(event: KeyEvent, forward: Boolean) {
        if (!config.smoothKeyScroll) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (forward) scrollDown() else scrollUp()
            }
            return
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> startHoldScroll(if (forward) 1 else -1)
            KeyEvent.ACTION_UP -> stopHoldScroll()
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        // Any key release ends an active hold-to-scroll, even if the branch below declines the
        // event (e.g. the menu opened mid-hold), so the frame loop can never be left running.
        if (isUp) stopHoldScroll()

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                }
                handleScrollKey(event, forward = !config.volumeKeysInverted)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                }
                handleScrollKey(event, forward = config.volumeKeysInverted)
                if (isUp && config.autoScrollVolumeTriplePress) handleVolumeUpMultiPress(event)
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> handleScrollKey(event, forward = false)

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> handleScrollKey(event, forward = true)

            // Unhandled by default, so they are free to claim for an external controller. Declined
            // while the menu is open so they keep working for normal d-pad/keyboard navigation.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            -> {
                if (!config.autoScrollKeyToggle || activity.viewModel.state.value.menuVisible) {
                    return false
                }
                if (isUp) setAutoScroll(!autoScrollActive)
            }
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4

// Both scroll speeds are stored as hundredths of a screen height per second.
private const val SCREEN_FRACTION_DENOMINATOR = 100f

// How long auto-scroll takes to ease from a standstill up to the configured speed.
private const val AUTO_SCROLL_RAMP_MILLIS = 750f

// Maximum gap between consecutive volume up presses for them to count as one multi-press.
private const val MULTI_PRESS_WINDOW_MILLIS = 400L
private const val VOLUME_PRESSES_TO_TOGGLE = 3

// Cap the per-frame time delta so a dropped frame or a paused app doesn't produce one huge jump.
private const val SCROLL_MAX_FRAME_SECONDS = 0.1f
private const val NANOS_PER_SECOND = 1_000_000_000f
