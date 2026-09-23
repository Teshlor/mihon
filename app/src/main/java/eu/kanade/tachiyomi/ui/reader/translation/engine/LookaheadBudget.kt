package eu.kanade.tachiyomi.ui.reader.translation.engine

import kotlin.math.max
import kotlin.math.min

/**
 * How far below the screen the long-strip reader lays out pages while translating, so they are
 * decoded, and translated, before the reader gets there.
 *
 * Every pixel of list laid out holds decoded page bitmap, so the distance is bounded by memory: a
 * share of the app's heap, at most [MAX_BUDGET_BYTES], spent on pages at [BYTES_PER_PIXEL] per
 * bitmap pixel. It is never more than [MAX_SCREENS] screens, nor less than the reader lays out
 * anyway.
 */
object LookaheadBudget {

    const val MAX_SCREENS = 3f

    /** Share of the app's standard heap ([android.app.ActivityManager.getMemoryClass]) to spend. */
    const val MEMORY_FRACTION = 0.25f

    const val MAX_BUDGET_BYTES = 64L * 1024 * 1024

    /** Decoded pages are taken to be ARGB_8888, the largest config they come in. */
    const val BYTES_PER_PIXEL = 4L

    private const val BYTES_PER_MIB = 1024L * 1024

    /**
     * Extra layout space in pixels.
     *
     * @param basePx what the reader lays out without translation, the least this returns.
     * @param viewportHeightPx height of the list's viewport.
     * @param viewWidthPx width of a page view; 0 when not known yet, which gives [basePx].
     * @param bitmapWidthPx width of the widest decoded page seen, or 0 for none yet, in which case
     * pages are taken to be decoded at the view's width.
     * @param memoryClassMb the app's standard heap size in MiB.
     */
    fun extraSpacePx(
        basePx: Int,
        viewportHeightPx: Int,
        viewWidthPx: Int,
        bitmapWidthPx: Int,
        memoryClassMb: Int,
    ): Int {
        if (viewWidthPx <= 0) return basePx
        val w = bitmapWidthPx.takeIf { it > 0 } ?: viewWidthPx
        // A page w bitmap pixels wide shown viewWidthPx wide has w / viewWidthPx bitmap rows of w
        // pixels per pixel of list height.
        val bytesPerViewPx = (BYTES_PER_PIXEL * w * w / viewWidthPx).coerceAtLeast(1L)
        val heapBytes = memoryClassMb.coerceAtLeast(0) * BYTES_PER_MIB
        val budget = min((heapBytes * MEMORY_FRACTION.toDouble()).toLong(), MAX_BUDGET_BYTES)
        val most = max(basePx.toLong(), (viewportHeightPx * MAX_SCREENS).toLong())
        return (budget / bytesPerViewPx).coerceIn(basePx.toLong(), most).toInt()
    }
}
