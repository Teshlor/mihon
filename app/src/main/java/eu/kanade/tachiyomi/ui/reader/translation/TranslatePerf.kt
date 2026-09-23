package eu.kanade.tachiyomi.ui.reader.translation

import android.os.SystemClock
import android.util.Log
import eu.kanade.tachiyomi.ui.reader.translation.engine.PerfStats
import java.util.Locale

/**
 * Performance log for translate-as-you-scroll, for tuning on a phone:
 * `adb logcat -s TranslatePerf:I`.
 *
 * One `key=value` line per event. Lines carry page numbers, sizes, counts and times only, never any
 * text from a page, original or translated.
 *
 * Every call site is wrapped in `if (TranslatePerf.ENABLED)`, so turning [ENABLED] off lets the
 * compiler and R8 drop all of it.
 */
object TranslatePerf {

    const val ENABLED = true

    const val TAG = "TranslatePerf"

    /** A summary line is logged after this many bands have been read. */
    const val SUMMARY_EVERY_BANDS = 25

    fun now(): Long = SystemClock.uptimeMillis()

    fun log(line: String) {
        Log.i(TAG, line)
    }
}

/**
 * Totals behind the `summary` line. Main thread only.
 */
internal class TranslatePerfSummary {

    private val stats = PerfStats()
    private var windowStart = 0L
    private var windowOpen = false

    /** Starts the window at the first event after a summary, so idle time before it doesn't count. */
    private fun open() {
        if (windowOpen) return
        windowOpen = true
        windowStart = TranslatePerf.now()
    }

    fun requested(lead: Int?) {
        open()
        if (lead != null) stats.add(LEAD, lead)
    }

    /**
     * One band read. [busyMillis] is how long the recognizer was busy with it.
     */
    fun band(copyMillis: Int, ocrMillis: Int, blank: Boolean, busyMillis: Long) {
        open()
        stats.count(BANDS)
        stats.add(COPY, copyMillis)
        if (blank) stats.count(BLANK) else stats.add(OCR, ocrMillis)
        stats.count(OCR_BUSY, busyMillis)
        if (stats.counter(BANDS) >= TranslatePerf.SUMMARY_EVERY_BANDS) flush()
    }

    /**
     * One band's bubbles translated and shown.
     */
    fun published(queuedMillis: Int, translateMillis: Int, strings: Int, hits: Int, buckets: IntArray) {
        open()
        stats.add(TQ, queuedMillis)
        stats.add(TR, translateMillis)
        stats.count(STRS, strings.toLong())
        stats.count(HITS, hits.toLong())
        stats.count(TR_BUSY, translateMillis.toLong())
        PerfStats.Bucket.entries.forEach { stats.count(it.name, buckets[it.ordinal].toLong()) }
    }

    fun failed() {
        open()
        stats.count(FAIL)
    }

    fun dropped() {
        open()
        stats.count(DROPS)
    }

    /**
     * Logs the summary of everything since the last one, if there is anything, and starts again.
     */
    fun flush() {
        val now = TranslatePerf.now()
        val bands = stats.counter(BANDS)
        val ahead = stats.counter(PerfStats.Bucket.AHEAD.name)
        val lower = stats.counter(PerfStats.Bucket.LOWER.name)
        val past = stats.counter(PerfStats.Bucket.PAST_MID.name)
        val gone = stats.counter(PerfStats.Bucket.GONE.name)
        val bubbles = ahead + lower + past + gone
        if (bands == 0L && bubbles == 0L && stats.counter(FAIL) == 0L && stats.counter(DROPS) == 0L) return

        val elapsed = (now - windowStart).coerceAtLeast(1L)
        val late = if (bubbles == 0L) 0f else (past + gone) * 100f / bubbles
        TranslatePerf.log(
            "summary bands=$bands blank=${stats.counter(BLANK)} fail=${stats.counter(FAIL)}" +
                " ocr50=${stats.p50(OCR)} ocr90=${stats.p90(OCR)} copy90=${stats.p90(COPY)}" +
                " tr50=${stats.p50(TR)} tr90=${stats.p90(TR)} strs=${stats.counter(STRS)}" +
                " hits=${stats.counter(HITS)} tq90=${stats.p90(TQ)} bubbles=$bubbles" +
                " ahead=$ahead lower=$lower past=$past gone=$gone" +
                " late%=${String.format(Locale.ROOT, "%.1f", late)} lead50=${stats.p50(LEAD)}" +
                " drops=${stats.counter(DROPS)}" +
                " ocrBusy%=${stats.counter(OCR_BUSY) * 100 / elapsed}" +
                " trBusy%=${stats.counter(TR_BUSY) * 100 / elapsed}",
        )
        stats.reset()
        windowOpen = false
    }

    private companion object {
        const val BANDS = "bands"
        const val BLANK = "blank"
        const val FAIL = "fail"
        const val DROPS = "drops"
        const val OCR = "ocr"
        const val COPY = "copy"
        const val TR = "tr"
        const val TQ = "tq"
        const val LEAD = "lead"
        const val STRS = "strs"
        const val HITS = "hits"
        const val OCR_BUSY = "ocrBusy"
        const val TR_BUSY = "trBusy"
    }
}
