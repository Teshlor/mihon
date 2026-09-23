package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.translation.engine.BandPriority
import eu.kanade.tachiyomi.ui.reader.translation.engine.Box
import eu.kanade.tachiyomi.ui.reader.translation.engine.LineGrouper
import eu.kanade.tachiyomi.ui.reader.translation.engine.PatchColour
import eu.kanade.tachiyomi.ui.reader.translation.engine.PerfStats
import eu.kanade.tachiyomi.ui.reader.translation.engine.StripPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Translates the pages of the long-strip reader in the background and hands each page's holder its
 * translation band by band as it lands. Bands are read in any order, the one the reader is about to
 * reach first, whichever page it is on (see [BandPriority]).
 *
 * One page band is read at a time, and one band's text is translated at a time, so there is
 * exactly one recognizer and one translator in flight. After every band the next band to read, and
 * the next to translate, is picked again from where the pages are on screen right now, so scrolling
 * or jumping re-prioritises within one band's work. Pixel work runs on [Dispatchers.Default]; the
 * main thread only copies the band out of the page bitmap (the same thread that recycles that
 * bitmap, so the two can never race) and publishes results.
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
         * Top of the page view in the reader's list, in pixels from the top of the list's viewport
         * (negative once scrolled past), or null when the page view isn't in the list.
         */
        val topInViewport: Int?

        /** Height of the reader's list viewport in pixels. */
        val viewportHeight: Int

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

    /** Bands read and waiting to be translated, in the order they were read. */
    private val results = mutableListOf<BandResult>()
    private val resultsWake = Channel<Unit>(Channel.CONFLATED)

    private var modelsReady: Pair<String, String>? = null
    private var modelsRetryAtMillis = 0L
    private var wifiNoticeShown = false
    private var failureNoticeShown = false

    private val perf = if (TranslatePerf.ENABLED) TranslatePerfSummary() else null

    private class PageJob(
        val key: TranslationKey,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val bands: List<StripPlanner.Band>,
        /** Bands read, in any order. */
        val done: MutableSet<Int>,
        /** Lines the bands in [done] held back, see [StripPlanner.settle]. */
        var held: List<CarriedLine>,
        val blocks: MutableList<Bubble>,
    ) {
        var target: Target? = null

        /** Bands being read right now. */
        val reading = mutableSetOf<Int>()
        var inFlight = 0
        var abandoned = false
        val isRecognized: Boolean get() = done.size == bands.size

        /** When the job was created, for [TranslatePerf]. */
        val createdAt = TranslatePerf.now()
    }

    /**
     * What one band produced: bubbles ready to translate, and the page's state after it.
     */
    private class BandResult(
        val job: PageJob,
        val bandIndex: Int,
        val drafts: List<Draft>,
    ) {
        /** When it was handed to the translator, for [TranslatePerf]. */
        val queuedAt = TranslatePerf.now()
    }

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
            removeJob(job)
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
                done = (resume?.status as? PageStatus.Partial)?.bandsDone.orEmpty()
                    .filterTo(mutableSetOf()) { it in bands.indices },
                held = resume?.carried.orEmpty(),
                blocks = resume?.blocks.orEmpty().toMutableList(),
            )
            jobs[key] = job
            if (TranslatePerf.ENABLED) {
                val lead = target.topInViewport?.let { it - target.viewportHeight }
                perf?.requested(lead)
                TranslatePerf.log(
                    "req p=${key.pageIndex} ch=${key.chapterId} w=${bitmap.width} h=${bitmap.height}" +
                        " bands=${bands.size} lead=${lead ?: "na"} resume=${job.done.size}",
                )
            }
        }
        val attached = job
        attached.target = target
        if (attached.blocks.isNotEmpty()) {
            showCached(key, TranslatedPage(PageStatus.Partial(emptySet()), attached.blocks.toList(), 0, 0), target) {
                active && attached.target === target
            }
        }
        wake.trySend(Unit)

        return Handle {
            active = false
            if (attached.target === target) {
                attached.target = null
                if (attached.inFlight == 0) removeJob(attached)
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
        // Lets the translator drop the results it was still to translate.
        resultsWake.trySend(Unit)
        if (TranslatePerf.ENABLED) perf?.flush()
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
        cancelAll() // Also logs the last performance summary.
        scope.cancel()
        results.clear()
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
            val next = pickNext()
            if (next == null) {
                wake.receive()
                continue
            }
            val (job, bandIndex) = next
            if (!ensureModels(job.key.sourceLang, job.key.targetLang)) {
                // Waiting for Wi-Fi, or for Play services. Try again when a page asks, or soon.
                withTimeoutOrNull(MODELS_RETRY_MILLIS) { wake.receive() }
                continue
            }
            if (!job.abandoned) recognizeBand(job, bandIndex)
        }
    }

    /**
     * The band to read next, over every page, by [BandPriority], or null when there is nothing to
     * do. Where the pages are on screen is read now, so scrolling needs no bookkeeping.
     */
    private fun pickNext(): Pair<PageJob, Int>? {
        var best: PageJob? = null
        var bestBand = -1
        var bestPriority = Float.POSITIVE_INFINITY
        for (job in jobs.values) {
            if (job.abandoned || job.isRecognized) continue
            val position = job.target?.position ?: continue
            if (position == RecyclerView.NO_POSITION) continue
            for (band in job.bands) {
                if (band.index in job.done || band.index in job.reading) continue
                val priority = priorityOf(job, band)
                if (priority < bestPriority) {
                    best = job
                    bestBand = band.index
                    bestPriority = priority
                }
            }
        }
        return best?.let { it to bestBand }
    }

    /**
     * [BandPriority] of [band] of [job]'s page, where the page is on screen right now.
     */
    private fun priorityOf(job: PageJob, band: StripPlanner.Band): Float {
        val target = job.target ?: return BandPriority.DETACHED
        val top = target.topInViewport ?: return BandPriority.DETACHED
        val width = target.viewWidth
        if (width <= 0) return BandPriority.DETACHED
        return BandPriority.of(top + band.bottom * width.toFloat() / job.bitmapWidth, target.viewportHeight)
    }

    private suspend fun ensureModels(source: String, target: String): Boolean {
        val pair = source to target
        if (modelsReady == pair) return true
        if (SystemClock.uptimeMillis() < modelsRetryAtMillis) return false
        val outcome = attempt {
            translator.ensureModels(source, target) {
                logcat { "Downloading translation models for $source -> $target" }
            }
        }
        val e = outcome.exceptionOrNull()
        if (e == null) {
            modelsReady = pair
            return true
        }
        modelsRetryAtMillis = SystemClock.uptimeMillis() + MODELS_RETRY_MILLIS
        if (e is TranslationNeedsWifiException) {
            if (!wifiNoticeShown) {
                wifiNoticeShown = true
                listener.onNeedsWifi()
            }
        } else {
            logcat(LogPriority.ERROR, e) { "Couldn't prepare translation models" }
            notifyFailure(e)
        }
        return false
    }

    private suspend fun recognizeBand(job: PageJob, bandIndex: Int) {
        val band = job.bands[bandIndex]
        val prio = if (TranslatePerf.ENABLED) screenLabel(job, band) else null
        val copyStart = if (TranslatePerf.ENABLED) TranslatePerf.now() else 0L
        val copy = copyBand(job, band)
        if (copy == null) {
            // The page's bitmap is gone; the holder will cancel or ask again.
            job.target = null
            if (job.inFlight == 0) removeJob(job)
            return
        }
        val ocrStart = if (TranslatePerf.ENABLED) TranslatePerf.now() else 0L

        job.reading += bandIndex
        job.inFlight++
        var handedOff = false
        try {
            val source = job.key.sourceLang
            var ocrMillis = 0L
            val outcome = attempt {
                val lines = withContext(Dispatchers.Default) { translator.recognize(copy, source) }
                if (TranslatePerf.ENABLED) ocrMillis = TranslatePerf.now() - ocrStart
                val found = lines.map { it.copy(box = it.box.offset(0f, band.top.toFloat())) }
                // Back on the main thread, which is the only one that touches the job's bands and
                // held lines. One band is read at a time, so they can't change until it's done.
                val advance = StripPlanner.settle(
                    job.bands,
                    bandIndex,
                    job.done.toSet(),
                    job.held,
                    found,
                    LineGrouper::group,
                )
                val drafts = withContext(Dispatchers.Default) {
                    advance.publish.mapNotNull { draft(it, copy, band, job, source) }
                }
                Triple(advance, drafts, found.size)
            }
            // Only once ML Kit is done with the copy. When this coroutine is cancelled (attempt
            // rethrows) or the recognition fails, ML Kit may still be reading it, so the GC takes it.
            if (outcome.isSuccess) copy.recycle()
            val (advance, drafts, lineCount) = outcome.getOrElse { e ->
                logcat(LogPriority.ERROR, e) { "Couldn't read page ${job.key.pageIndex} band $bandIndex" }
                if (TranslatePerf.ENABLED) logFailure(job, bandIndex, e, retry = false)
                fail(job, e)
                return
            }
            if (TranslatePerf.ENABLED) {
                val now = TranslatePerf.now()
                TranslatePerf.log(
                    "band p=${job.key.pageIndex} b=$bandIndex/${job.bands.size} order=${job.done.size} prio=$prio" +
                        " sinceReq=${now - job.createdAt} copy=${ocrStart - copyStart} blank=0 ocr=$ocrMillis" +
                        " lines=$lineCount held=${advance.carried.size}",
                )
                perf?.band(
                    (ocrStart - copyStart).toInt(),
                    ocrMillis.toInt(),
                    blank = false,
                    busyMillis = now - ocrStart,
                )
            }
            job.done += bandIndex
            job.held = advance.carried
            if (!job.abandoned) {
                results += BandResult(job, bandIndex, drafts)
                resultsWake.trySend(Unit)
                handedOff = true
            }
        } finally {
            job.reading -= bandIndex
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
        while (scope.isActive) {
            val result = takeResult()
            if (result == null) {
                resultsWake.receive()
                continue
            }
            val job = result.job
            try {
                attempt { translateBand(result) }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Couldn't translate page ${job.key.pageIndex}" }
                    if (TranslatePerf.ENABLED) logFailure(job, result.bandIndex, e, retry = false)
                    fail(job, e)
                }
            } finally {
                finishInFlight(job)
            }
        }
    }

    /**
     * Takes the waiting result whose band comes first by [BandPriority], the earliest read among
     * equals, after dropping the results of abandoned pages. Null when none is waiting.
     */
    private fun takeResult(): BandResult? {
        val abandoned = results.filter { it.job.abandoned }
        if (abandoned.isNotEmpty()) {
            results.removeAll(abandoned)
            abandoned.forEach { finishInFlight(it.job) }
        }
        val next = results.minByOrNull { priorityOf(it.job, it.job.bands[it.bandIndex]) } ?: return null
        results.remove(next)
        return next
    }

    /**
     * Translates one band's bubbles, adds them to the page and shows them.
     */
    private suspend fun translateBand(result: BandResult) {
        val job = result.job
        val start = if (TranslatePerf.ENABLED) TranslatePerf.now() else 0L
        val texts = result.drafts.map { it.original }
        val translated = if (texts.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                translator.translateText(texts, job.key.sourceLang, job.key.targetLang)
            }
        }
        val translateMillis = if (TranslatePerf.ENABLED) TranslatePerf.now() - start else 0L
        if (job.abandoned) return

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
        if (job.inFlight == 1) {
            // Nothing else of this page is being read or waiting to be translated, so every band in
            // done has its bubbles in blocks: a consistent point to resume from. Not otherwise,
            // since the other results are dropped if the job is abandoned, and their bands would
            // then be skipped on resuming.
            cache[job.key] = TranslatedPage(
                status = if (job.isRecognized) PageStatus.Complete else PageStatus.Partial(job.done.toSet()),
                blocks = job.blocks.toList(),
                bitmapWidth = job.bitmapWidth,
                bitmapHeight = job.bitmapHeight,
                carried = job.held,
            )
        }
        val buckets = if (bubbles.isNotEmpty()) publish(job, before) else null
        if (TranslatePerf.ENABLED) {
            val counts = buckets ?: IntArray(PerfStats.Bucket.entries.size)
            val queued = start - result.queuedAt
            TranslatePerf.log(
                "pub p=${job.key.pageIndex} b=${result.bandIndex}/${job.bands.size} tq=$queued tr=$translateMillis" +
                    " strs=${texts.size} hits=0 new=${bubbles.size}" +
                    " ahead=${counts[PerfStats.Bucket.AHEAD.ordinal]} lower=${counts[PerfStats.Bucket.LOWER.ordinal]}" +
                    " past=${counts[PerfStats.Bucket.PAST_MID.ordinal]} gone=${counts[PerfStats.Bucket.GONE.ordinal]}" +
                    " sinceReq=${TranslatePerf.now() - job.createdAt}",
            )
            perf?.published(queued.toInt(), translateMillis.toInt(), texts.size, 0, counts)
        }
    }

    /**
     * Lays out the page's bubbles from [firstNew] on and shows the page's bubbles so far, the new
     * ones fading in.
     *
     * Returns how many of the new bubbles were in each [PerfStats.Bucket] when shown, for
     * [TranslatePerf]; all [PerfStats.Bucket.GONE] when they couldn't be shown.
     */
    private suspend fun publish(job: PageJob, firstNew: Int): IntArray {
        val buckets = IntArray(PerfStats.Bucket.entries.size)
        val gone = job.blocks.size - firstNew
        buckets[PerfStats.Bucket.GONE.ordinal] = gone
        val target = job.target ?: return buckets
        val width = target.viewWidth
        if (width <= 0) return buckets
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
        if (job.target === target && !job.abandoned) {
            target.show(all, fadeInFrom = firstNew)
            if (TranslatePerf.ENABLED) {
                buckets.fill(0)
                val top = target.topInViewport
                val scale = width.toFloat() / job.bitmapWidth
                for (i in firstNew..<job.blocks.size) {
                    val bubbleTop = top?.let { it + job.blocks[i].rectNorm.top * job.bitmapHeight * scale }
                    buckets[PerfStats.bucketOf(bubbleTop, target.viewportHeight).ordinal]++
                }
            }
        }
        return buckets
    }

    /**
     * Where [band] of [job]'s page is on screen, for [TranslatePerf].
     */
    private fun screenLabel(job: PageJob, band: StripPlanner.Band): String {
        val target = job.target ?: return "detached"
        val top = target.topInViewport ?: return "detached"
        val width = target.viewWidth
        if (width <= 0) return "detached"
        val scale = width.toFloat() / job.bitmapWidth
        return when {
            top + band.bottom * scale <= 0f -> "behind"
            top + band.top * scale >= target.viewportHeight -> "ahead"
            else -> "visible"
        }
    }

    private fun logFailure(job: PageJob, bandIndex: Int, e: Throwable, retry: Boolean) {
        TranslatePerf.log(
            "fail p=${job.key.pageIndex} b=$bandIndex/${job.bands.size} err=${e::class.simpleName}" +
                " action=${if (retry) "retry" else "page"}",
        )
        perf?.failed()
    }

    /**
     * Forgets [job], if it is still the job for its page.
     */
    private fun removeJob(job: PageJob) {
        if (jobs[job.key] !== job) return
        jobs.remove(job.key)
        if (TranslatePerf.ENABLED && !job.isRecognized) {
            TranslatePerf.log("drop p=${job.key.pageIndex} done=${job.done.size}/${job.bands.size}")
            perf?.dropped()
        }
    }

    private fun fail(job: PageJob, e: Throwable) {
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

    private fun notifyFailure(e: Throwable) {
        if (failureNoticeShown) return
        failureNoticeShown = true
        listener.onFailed(e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty())
    }

    private fun finishInFlight(job: PageJob) {
        job.inFlight--
        val finished = job.isRecognized && job.inFlight == 0
        if (job.inFlight == 0 && (job.target == null || finished)) removeJob(job)
        wake.trySend(Unit)
    }

    companion object {
        /**
         * Bump when a change to the pipeline makes cached translations wrong.
         */
        const val PIPELINE_VERSION = 1

        private const val MODELS_RETRY_MILLIS = 30_000L
    }
}

/**
 * Runs one piece of background work and returns what it threw instead of throwing it, so one page
 * that fails can't stop the loop working through them.
 *
 * What it threw is rethrown only when the calling coroutine has itself been cancelled. A
 * [CancellationException] while the coroutine is still active came from the work, such as an ML
 * Kit Task that was cancelled (its await() throws one), and is that work's failure.
 */
internal suspend inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }
