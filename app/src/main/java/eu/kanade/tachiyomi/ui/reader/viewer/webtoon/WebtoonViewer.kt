package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.ActivityManager
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
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
import eu.kanade.tachiyomi.ui.reader.translation.PageTranslation
import eu.kanade.tachiyomi.ui.reader.translation.PageTranslationScheduler
import eu.kanade.tachiyomi.ui.reader.translation.TranslationKey
import eu.kanade.tachiyomi.ui.reader.translation.engine.LookaheadBudget
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
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

    /**
     * Whether pages are translated as they scroll into view. Session only; turned on and off
     * from the reader's top bar.
     */
    var translationEnabled = false
        private set

    /**
     * Created the first time translation is turned on, so readers who never use it pay nothing.
     */
    private var translationScheduler: PageTranslationScheduler? = null

    /**
     * Width of the widest page bitmap translation has been asked for, which sizes the look-ahead.
     */
    private var widestTranslatedBitmap = 0

    private var scrollLoopRunning = false
    private var scrollLoopLastFrameNanos = 0L

    /**
     * Consecutive quick presses of volume up, used to detect the triple press that toggles
     * auto-scroll. Reset whenever the gap between presses exceeds [MULTI_PRESS_WINDOW_MILLIS].
     */
    private var volumeUpPressCount = 0
    private var volumeUpLastPressMillis = 0L

    /**
     * Page whose saved scroll offset is still waiting to be applied, and how far into it to go.
     * Held until the image decodes, because the offset is a fraction of the page's real height and
     * a placeholder is the wrong size to measure against. Cleared if the reader touches the screen
     * first, so restoring never yanks the page out from under them.
     */
    private var pendingRestorePage: ReaderPage? = null
    private var pendingRestoreFraction = 0.0

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
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        setAutoScroll(false)
                        pendingRestorePage = null
                    }
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
        translationScheduler?.destroy()
        translationScheduler = null
        scope.cancel()
    }

    /**
     * Turns translate-as-you-scroll on or off for the pages in this viewer.
     */
    fun setTranslationEnabled(enabled: Boolean) {
        if (enabled && !PageTranslation.isAvailable) return
        if (translationEnabled == enabled) return
        translationEnabled = enabled
        if (enabled) {
            translationScheduler().resetNotices()
            forEachPageHolder { it.startTranslation() }
        } else {
            forEachPageHolder { it.stopTranslation() }
            translationScheduler?.cancelAll()
        }
        if (updateTranslationLookahead()) recycler.requestLayout()
    }

    /**
     * Asks for [page] to be translated, see [PageTranslationScheduler.request].
     */
    fun requestTranslation(page: ReaderPage, target: PageTranslationScheduler.Target): PageTranslationScheduler.Handle {
        val bitmapWidth = target.bitmap?.width ?: 0
        if (bitmapWidth > widestTranslatedBitmap) {
            widestTranslatedBitmap = bitmapWidth
            // This can run during a layout, so the new space applies from the next one.
            updateTranslationLookahead()
        }
        return translationScheduler().request(translationKey(page), target)
    }

    /**
     * Lays out pages further below the screen while translating, so they are decoded and
     * translated before the reader gets there, as far as [LookaheadBudget] allows. Without
     * translation, or on a low-RAM phone, it is the usual [scrollDistance].
     *
     * Returns whether the space changed; it applies from the next layout or scroll.
     */
    private fun updateTranslationLookahead(): Boolean {
        val activityManager = activity.getSystemService<ActivityManager>()
        val extra = if (!translationEnabled || activityManager == null || activityManager.isLowRamDevice) {
            scrollDistance
        } else {
            // The page view's width, as WebtoonPageHolder lays it out.
            val listWidth = recycler.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val sideMargin = (Resources.getSystem().displayMetrics.widthPixels * (config.sidePadding / 100f)).toInt()
            LookaheadBudget.extraSpacePx(
                basePx = scrollDistance,
                viewportHeightPx = recycler.height.takeIf { it > 0 } ?: screenHeight,
                viewWidthPx = (listWidth - 2 * sideMargin).coerceAtLeast(0),
                bitmapWidthPx = widestTranslatedBitmap,
                memoryClassMb = activityManager.memoryClass,
            )
        }
        if (extra == layoutManager.extraLayoutSpace) return false
        layoutManager.extraLayoutSpace = extra
        return true
    }

    private fun translationScheduler(): PageTranslationScheduler =
        translationScheduler ?: PageTranslationScheduler(
            activity,
            object : PageTranslationScheduler.Listener {
                override fun onNeedsWifi() {
                    activity.toast(MR.strings.translation_needs_wifi, Toast.LENGTH_LONG)
                }

                override fun onFailed(reason: String) {
                    activity.toast(
                        activity.stringResource(MR.strings.translation_failed_reason, reason),
                        Toast.LENGTH_LONG,
                    )
                }
            },
        ).also { translationScheduler = it }

    private fun translationKey(page: ReaderPage): TranslationKey = TranslationKey(
        chapterId = page.chapter.chapter.id ?: -1L,
        pageIndex = page.index,
        image = page.imageUrl ?: page.url,
        sourceLang = readerPreferences.translationSourceLanguage.get(),
        targetLang = readerPreferences.translationTargetLanguage.get().ifEmpty {
            PageTranslation.defaultTargetLanguage
        },
        pipelineVersion = PageTranslationScheduler.PIPELINE_VERSION,
        cropBorders = config.imageCropBorders,
        dualSplit = config.dualPageSplit,
        dualSplitInvert = config.dualPageInvert,
        rotateToFit = config.dualPageRotateToFit,
        rotateToFitInvert = config.dualPageRotateToFitInvert,
    )

    private inline fun forEachPageHolder(action: (WebtoonPageHolder) -> Unit) {
        for (i in 0..<recycler.childCount) {
            (recycler.getChildViewHolder(recycler.getChildAt(i)) as? WebtoonPageHolder)?.let(action)
        }
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
            val requested = pages[min(chapters.currChapter.requestedPage, pages.lastIndex)]
            moveToPage(requested)
            val offset = chapters.currChapter.requestedPageOffset
            if (offset > 0.0) {
                pendingRestorePage = requested
                pendingRestoreFraction = offset
            }
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
        activity.viewModel.setAutoScrollActive(enabled)
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
     * How far the reader has scrolled into the selected page, as a fraction of its height.
     */
    override fun currentPageOffsetFraction(): Double {
        val page = currentPage as? ReaderPage ?: return 0.0
        val position = adapter.items.indexOf(page)
        if (position == RecyclerView.NO_POSITION) return 0.0
        val view = layoutManager.findViewByPosition(position) ?: return 0.0
        if (view.height <= 0) return 0.0
        return ((-view.top).toDouble() / view.height).coerceIn(0.0, 1.0)
    }

    /**
     * Moves to [page] and then [offsetFraction] of the way down it.
     */
    override fun moveToPageWithOffset(page: ReaderPage, offsetFraction: Double) {
        moveToPage(page)
        if (offsetFraction <= 0.0) return
        pendingRestorePage = page
        pendingRestoreFraction = offsetFraction
        // Covers the case where the image is already decoded, so no decode callback is coming.
        recycler.post { applyPendingRestore() }
    }

    /**
     * Called by a page holder once its image has decoded and the view has its real height. This is
     * the earliest point at which a saved fractional offset can be turned into a pixel distance.
     */
    fun onPageImageDecoded(page: ReaderPage) {
        if (page !== pendingRestorePage) return
        recycler.post { applyPendingRestore() }
    }

    /**
     * Applies a pending offset if its page is laid out with a real height, and leaves it pending
     * otherwise so a later decode or layout can retry.
     */
    private fun applyPendingRestore() {
        val page = pendingRestorePage ?: return
        val position = adapter.items.indexOf(page)
        if (position == RecyclerView.NO_POSITION) return
        val view = layoutManager.findViewByPosition(position) ?: return
        if (view.height <= 0) return

        val fraction = pendingRestoreFraction
        pendingRestorePage = null
        pendingRestoreFraction = 0.0
        recycler.scrollBy(0, view.top + (fraction * view.height).toInt())
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
