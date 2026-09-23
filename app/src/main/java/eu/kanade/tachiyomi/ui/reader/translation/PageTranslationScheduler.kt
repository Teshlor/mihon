package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.translation.engine.Box
import eu.kanade.tachiyomi.ui.reader.translation.engine.LineGrouper
import eu.kanade.tachiyomi.ui.reader.translation.engine.PatchColour
import eu.kanade.tachiyomi.ui.reader.translation.engine.StripPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs

/**
 * Translates the pages of the long-strip reader in the background, the page in the middle of the
 * screen first, and hands each page's holder its translation band by band as it lands.
 *
 * One page band is read at a time, and one band's text is translated at a time, so there is
 * exactly one recognizer and one translator in flight. After every band the next one is picked
 * again, so scrolling or jumping re-prioritises within one band's work. Pixel work runs on
 * [Dispatchers.Default]; the main thread only copies the band out of the page bitmap (the same
 * thread that recycles that bitmap, so the two can never race) and publishes results.
 *
 * Everything public is main thread only.
 */
class PageTranslationScheduler(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** A language model has to be downloaded and the phone isn't on Wi-Fi. */
        fun onNeedsWifi()

        /** A page couldn't be translated, for [reason]. */
        fun onFailed(reason: String)
    }

    /**
     * A page on screen that wants its translation. Called on the main thread only.
     */
    interface Target {
        /** Adapter position of the page, or [RecyclerView.NO_POSITION]. */
        val position: Int

        /** The page's decoded bitmap, or null once it has been let go. */
        val bitmap: Bitmap?

        /** Width of the page view in unzoomed pixels, 0 before it is laid out. */
        val viewWidth: Int

        /**
         * Shows [blocks] on the page. Blocks from [fadeInFrom] on are new and fade in.
         */
        fun show(blocks: List<OverlayBlock>, fadeInFrom: Int)
    }

    /**
     * Returned by [request]. Cancel it before the page's bitmap is recycled or replaced.
     */
    fun interface Handle {
        fun cancel()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val translator = PageTranslation.create(context.applicationContext)
    private val layouts = BubbleLayouts(context)
    private val cache = TranslationCache()
    private val fitted = object : LinkedHashMap<Pair<TranslationKey, Int>, List<OverlayBlock>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TranslationKey, Int>, List<OverlayBlock>>) =
            size > TranslationCache.DEFAULT_MAX_PAGES
    }
    private val jobs = LinkedHashMap<TranslationKey, PageJob>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val translateQueue = Channel<BandResult>(Channel.UNLIMITED)

    private var focus = 0
    private var modelsReady: Pair<String, String>? = null
    private var modelsRetryAtMillis = 0L
    private var wifiNoticeShown = false
    private var failureNoticeShown = false

    private class PageJob(
        val key: TranslationKey,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val bands: List<StripPlanner.Band>,
        var nextBand: Int,
        var carried: List<CarriedLine>,
        val blocks: MutableList<Bubble>,
    ) {
        var target: Target? = null
        var recognizing = false
        var inFlight = 0
        var abandoned = false
        val isRecognized: Boolean get() = nextBand >= bands.size
    }

    /**
     * What one band produced: bubbles ready to translate, and the page's state after it.
     */
    private class BandResult(
        val job: PageJob,
        val bandsDone: Int,
        val drafts: List<Draft>,
        val carried: List<CarriedLine>,
    )

    private class Draft(
        val rectNorm: Box,
        val lineHeightNorm: Float,
        val original: String,
        val confidence: Float,
        val fill: Int,
    )

    init {
        scope.launch { recognizeLoop() }
        scope.launch { translateLoop() }
    }

    /**
     * Sets the adapter position of the page in the middle of the screen, which is translated
     * first.
     */
    fun setFocus(position: Int) {
        if (position == focus) return
        focus = position
        wake.trySend(Unit)
    }

    /**
     * Starts translating the page identified by [key] for [target], or shows it at once if it's
     * already done. Call [Handle.cancel] when the page leaves the holder.
     */
    fun request(key: TranslationKey, target: Target): Handle {
        val bitmap = target.bitmap ?: return Handle {}
        var active = true

        var cached = cache[key]
        if (cached?.status is PageStatus.Failed) {
            // Give a failed page another go each time it comes back into view.
            cache.remove(key)
            cached = null
        }
        val sameSize = cached != null && cached.bitmapWidth == bitmap.width && cached.bitmapHeight == bitmap.height
        if (cached != null && cached.isComplete) {
            showCached(key, cached, target) { active }
            return Handle { active = false }
        }

        var job = jobs[key]
        if (job != null && (job.bitmapWidth != bitmap.width || job.bitmapHeight != bitmap.height)) {
            // Decoded at a different size (a rotation, say): bands and held lines no longer match.
            job.abandoned = true
            jobs.remove(key)
            job = null
        }
        if (job == null) {
            val resume = cached?.takeIf { sameSize && it.status is PageStatus.Partial }
            val bands = StripPlanner.plan(bitmap.width, bitmap.height)
            job = PageJob(
                key = key,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                bands = bands,
                nextBand = (resume?.status as? PageStatus.Partial)?.bandsDone?.coerceIn(0, bands.size) ?: 0,
                carried = resume?.carried.orEmpty(),
                blocks = resume?.blocks.orEmpty().toMutableList(),
            )
            jobs[key] = job
        }
        val attached = job
        attached.target = target
        if (attached.blocks.isNotEmpty()) {
            showCached(key, TranslatedPage(PageStatus.Partial(0), attached.blocks.toList(), 0, 0), target) {
                active && attached.target === target
            }
        }
        wake.trySend(Unit)

        return Handle {
            active = false
            if (attached.target === target) {
                attached.target = null
                if (attached.inFlight == 0 && jobs[key] === attached) jobs.remove(key)
            }
        }
    }

    /**
     * Stops all work, for when translation is turned off. Finished pages stay cached.
     */
    fun cancelAll() {
        jobs.values.forEach {
            it.abandoned = true
            it.target = null
        }
        jobs.clear()
    }

    /**
     * Shows the one-time notices again, for when translation is turned back on.
     */
    fun resetNotices() {
        wifiNoticeShown = false
        failureNoticeShown = false
        modelsRetryAtMillis = 0L
    }

    fun destroy() {
        cancelAll()
        scope.cancel()
        translateQueue.close()
        // Any recognition or translation still running in ML Kit fails harmlessly once closed.
        translator.close()
        cache.clear()
        fitted.clear()
    }

    private fun showCached(key: TranslationKey, page: TranslatedPage, target: Target, isActive: () -> Boolean) {
        val width = target.viewWidth
        if (width <= 0 || page.blocks.isEmpty()) return
        val bitmap = target.bitmap ?: return
        fitted[key to width]?.takeIf { it.size == page.blocks.size }?.let {
            target.show(it, fadeInFrom = it.size)
            return
        }
        val blocks = page.blocks
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                layouts.fit(blocks, width, bitmap.width, bitmap.height)
            }
            fitted[key to width] = result
            if (isActive()) target.show(result, fadeInFrom = result.size)
        }
    }

    private suspend fun recognizeLoop() {
        while (scope.isActive) {
            val job = pickNext()
            if (job == null) {
                wake.receive()
                continue
            }
            if (!ensureModels(job.key.sourceLang, job.key.targetLang)) {
                // Waiting for Wi-Fi, or for Play services. Try again on the next scroll, or soon.
                withTimeoutOrNull(MODELS_RETRY_MILLIS) { wake.receive() }
                continue
            }
            if (!job.abandoned) recognizeNextBand(job)
        }
    }

    /**
     * The runnable page closest to the focus, or null when there is nothing to do.
     */
    private fun pickNext(): PageJob? {
        return jobs.values
            .filter { !it.abandoned && !it.recognizing && !it.isRecognized }
            .mapNotNull { job ->
                val position = job.target?.position ?: return@mapNotNull null
                if (position == RecyclerView.NO_POSITION) null else job to priorityOf(position, focus)
            }
            .minByOrNull { it.second }
            ?.first
    }

    private suspend fun ensureModels(source: String, target: String): Boolean {
        val pair = source to target
        if (modelsReady == pair) return true
        if (SystemClock.uptimeMillis() < modelsRetryAtMillis) return false
        return try {
            translator.ensureModels(source, target) {
                logcat { "Downloading translation models for $source -> $target" }
            }
            modelsReady = pair
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationNeedsWifiException) {
            modelsRetryAtMillis = SystemClock.uptimeMillis() + MODELS_RETRY_MILLIS
            if (!wifiNoticeShown) {
                wifiNoticeShown = true
                listener.onNeedsWifi()
            }
            false
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Couldn't prepare translation models" }
            modelsRetryAtMillis = SystemClock.uptimeMillis() + MODELS_RETRY_MILLIS
            notifyFailure(e)
            false
        }
    }

    private suspend fun recognizeNextBand(job: PageJob) {
        val bandIndex = job.nextBand
        val band = job.bands[bandIndex]
        val copy = copyBand(job, band)
        if (copy == null) {
            // The page's bitmap is gone; the holder will cancel or ask again.
            job.target = null
            if (job.inFlight == 0 && jobs[job.key] === job) jobs.remove(job.key)
            return
        }

        job.recognizing = true
        job.inFlight++
        var handedOff = false
        try {
            val source = job.key.sourceLang
            val (advance, drafts) = try {
                val lines = withContext(Dispatchers.Default) { translator.recognize(copy, source) }
                withContext(Dispatchers.Default) {
                    val found = lines.map { it.copy(box = it.box.offset(0f, band.top.toFloat())) }
                    val advance = StripPlanner.advance(job.bands, bandIndex, job.carried, found, LineGrouper::group)
                    advance to advance.publish.mapNotNull { draft(it, copy, band, job, source) }
                }
            } finally {
                copy.recycle()
            }
            job.nextBand = bandIndex + 1
            job.carried = advance.carried
            if (!job.abandoned) {
                translateQueue.send(BandResult(job, bandIndex + 1, drafts, advance.carried))
                handedOff = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Couldn't read page ${job.key.pageIndex} band $bandIndex" }
            fail(job, e)
        } finally {
            job.recognizing = false
            if (!handedOff) finishInFlight(job)
        }
    }

    /**
     * Copies [band] out of the page bitmap. Main thread only, like the bitmap's recycling, so it
     * can't be recycled halfway through. Always returns a new bitmap the caller owns, or null if the
     * page's bitmap is gone or no longer the one the job was planned for.
     */
    private fun copyBand(job: PageJob, band: StripPlanner.Band): Bitmap? {
        val source = job.target?.bitmap ?: return null
        if (source.isRecycled || source.width != job.bitmapWidth || source.height != job.bitmapHeight) return null
        return try {
            if (band.top == 0 && band.height == source.height) {
                // createBitmap would hand back the source itself here.
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createBitmap(source, 0, band.top, source.width, band.height)
            }
        } catch (e: IllegalStateException) {
            null // Recycled after all
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Turns one bubble's lines into a draft ready to translate: its box, text and the colour
     * around it, sampled from [bandBitmap] (the part of the ring inside the band).
     */
    private fun draft(
        lines: List<RecognizedLine>,
        bandBitmap: Bitmap,
        band: StripPlanner.Band,
        job: PageJob,
        sourceTag: String,
    ): Draft? {
        val text = LineGrouper.joinText(lines, sourceTag)
        if (text.isBlank()) return null
        val box = Box.unionOf(lines.map { it.box })
        val lineHeight = LineGrouper.medianHeight(lines)

        val ring = PatchColour.ringRects(
            box.offset(0f, -band.top.toFloat()),
            PatchColour.ringPad(lineHeight),
            bandBitmap.width,
            bandBitmap.height,
        )
        val pixels = IntArray(ring.sumOf { it.width * it.height })
        var count = 0
        for (rect in ring) {
            bandBitmap.getPixels(pixels, count, rect.width, rect.left, rect.top, rect.width, rect.height)
            count += rect.width * rect.height
        }
        val sample = PatchColour.sample(pixels, count)

        val w = job.bitmapWidth.toFloat()
        val h = job.bitmapHeight.toFloat()
        return Draft(
            rectNorm = box.scale(1f / w, 1f / h),
            lineHeightNorm = lineHeight / w,
            original = text,
            confidence = lines.minOf { it.confidence },
            fill = sample.fill,
        )
    }

    private suspend fun translateLoop() {
        for (result in translateQueue) {
            val job = result.job
            try {
                val texts = result.drafts.map { it.original }
                val translated = if (texts.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        translator.translateText(texts, job.key.sourceLang, job.key.targetLang)
                    }
                }
                if (job.abandoned) continue

                val bubbles = result.drafts.zip(translated) { draft, english ->
                    Bubble(
                        rectNorm = draft.rectNorm,
                        lineHeightNorm = draft.lineHeightNorm,
                        original = draft.original,
                        translated = english.ifBlank { draft.original },
                        confidence = draft.confidence,
                        fill = BubbleFill.Patch(draft.fill),
                    )
                }
                val before = job.blocks.size
                job.blocks += bubbles
                val done = result.bandsDone >= job.bands.size
                cache[job.key] = TranslatedPage(
                    status = if (done) PageStatus.Complete else PageStatus.Partial(result.bandsDone),
                    blocks = job.blocks.toList(),
                    bitmapWidth = job.bitmapWidth,
                    bitmapHeight = job.bitmapHeight,
                    carried = result.carried,
                )
                if (bubbles.isNotEmpty()) publish(job, before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Couldn't translate page ${job.key.pageIndex}" }
                fail(job, e)
            } finally {
                finishInFlight(job)
            }
        }
    }

    /**
     * Lays out the page's bubbles from [firstNew] on and shows the page's bubbles so far, the new
     * ones fading in.
     */
    private suspend fun publish(job: PageJob, firstNew: Int) {
        val target = job.target ?: return
        val width = target.viewWidth
        if (width <= 0) return
        val cacheKey = job.key to width
        val existing = fitted[cacheKey]?.takeIf { it.size == firstNew }
        val toFit = if (existing !=
            null
        ) {
            job.blocks.subList(firstNew, job.blocks.size).toList()
        } else {
            job.blocks.toList()
        }
        val laidOut = withContext(Dispatchers.Default) {
            layouts.fit(toFit, width, job.bitmapWidth, job.bitmapHeight)
        }
        val all = if (existing != null) existing + laidOut else laidOut
        fitted[cacheKey] = all
        if (job.target === target && !job.abandoned) target.show(all, fadeInFrom = firstNew)
    }

    private fun fail(job: PageJob, e: Exception) {
        // A job replaced or cancelled meanwhile no longer speaks for its page.
        if (job.abandoned) return
        job.abandoned = true
        cache[job.key] = TranslatedPage(
            status = PageStatus.Failed(e.message ?: e::class.simpleName.orEmpty()),
            blocks = job.blocks.toList(),
            bitmapWidth = job.bitmapWidth,
            bitmapHeight = job.bitmapHeight,
        )
        if (jobs[job.key] === job) jobs.remove(job.key)
        notifyFailure(e)
    }

    private fun notifyFailure(e: Exception) {
        if (failureNoticeShown) return
        failureNoticeShown = true
        listener.onFailed(e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty())
    }

    private fun finishInFlight(job: PageJob) {
        job.inFlight--
        val finished = job.isRecognized && job.inFlight == 0
        if (job.inFlight == 0 && (job.target == null || finished) && jobs[job.key] === job) {
            jobs.remove(job.key)
        }
        wake.trySend(Unit)
    }

    companion object {
        /**
         * Bump when a change to the pipeline makes cached translations wrong.
         */
        const val PIPELINE_VERSION = 1

        private const val MODELS_RETRY_MILLIS = 30_000L

        /**
         * Lower is sooner: the page in the middle of the screen, then the next three, then the one
         * behind, then everything else by distance.
         */
        fun priorityOf(pageIndex: Int, centreIndex: Int): Int = when (val d = pageIndex - centreIndex) {
            0 -> 0
            in 1..3 -> d
            -1 -> 4
            else -> 10 + abs(d)
        }
    }
}
