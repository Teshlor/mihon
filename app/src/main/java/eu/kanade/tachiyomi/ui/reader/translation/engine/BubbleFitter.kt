package eu.kanade.tachiyomi.ui.reader.translation.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Measures wrapped text. The Android implementation lays the text out with StaticLayout; tests
 * use a simple fixed-advance model.
 */
interface TextMeasurer {

    /**
     * Lays [text] out at [sizePx] and wraps it to [widthPx].
     */
    fun measure(text: String, sizePx: Float, widthPx: Int): Measured

    /**
     * @param height height of the wrapped text.
     * @param widestWord width of the widest single word. When it is wider than the layout, the
     * word would be broken mid-word, which counts as not fitting.
     */
    data class Measured(val height: Float, val widestWord: Float)
}

/**
 * Sizes translated text to fit where the original lettering was, never cutting it off.
 *
 * Starts at [START_TO_LINE_HEIGHT] of the original line height and shrinks a pixel at a time
 * until it fits, down to a floor (11 sp on screen). At the floor, the box grows about its centre,
 * first up to [GROW_LIMIT] times its size and, if even that isn't enough, further still until the
 * text fits. All sizes are unzoomed view pixels; zooming the strip magnifies text and art alike.
 */
object BubbleFitter {

    const val START_TO_LINE_HEIGHT = 0.85f
    const val GROW_LIMIT = 1.4f
    const val GROW_STEP = 1.05f

    /** Past this, growing stops and the box is sized from the text itself. */
    const val GROW_GIVE_UP = 4f

    /**
     * @param sizePx text size to letter at.
     * @param box where to lay the text out, possibly larger than the box asked for.
     * @param overflowed true when the box had to grow past [GROW_LIMIT]; worth logging, since it
     * means the English covers more of the art than intended.
     */
    data class Fit(val sizePx: Float, val box: Box, val overflowed: Boolean)

    /**
     * @param text the translated text.
     * @param box the area the original lettering occupied, in view pixels.
     * @param lineHeightPx height of the original lines, in view pixels.
     * @param floorPx smallest text size allowed.
     * @param maxWidthPx width of the page in view pixels; the box never grows wider.
     */
    fun fit(
        text: String,
        box: Box,
        lineHeightPx: Float,
        floorPx: Float,
        maxWidthPx: Float,
        measurer: TextMeasurer,
    ): Fit {
        fun fits(size: Float, b: Box): Boolean {
            val width = floor(b.width).toInt()
            if (width <= 0) return false
            val measured = measurer.measure(text, size, width)
            return measured.height <= b.height && measured.widestWord <= width
        }

        // Shrink to the floor.
        var size = max(floorPx, floor(lineHeightPx * START_TO_LINE_HEIGHT))
        while (true) {
            if (fits(size, box)) return Fit(size, box, overflowed = false)
            if (size <= floorPx) break
            size = max(floorPx, size - 1f)
        }
        size = floorPx

        // Then grow the box, up to the limit and then past it.
        var factor = GROW_STEP
        while (factor <= GROW_GIVE_UP) {
            val grown = clampWidth(box.scaleAboutCenter(factor), maxWidthPx)
            if (fits(size, grown)) return Fit(size, grown, overflowed = factor > GROW_LIMIT + 1e-4f)
            factor *= GROW_STEP
        }

        // Still no good: a long word, or a lot of text for a tiny bubble. Take the widest box
        // growing allowed (or the widest word, if wider), no wider than the page, and make it as
        // tall as the text needs there. A word wider than the page is broken by the layout, which
        // is the only way left not to cut it off.
        val measuredWide = measurer.measure(text, size, floor(maxWidthPx).toInt().coerceAtLeast(1))
        val width = max(box.width * GROW_GIVE_UP, measuredWide.widestWord).coerceIn(1f, max(1f, maxWidthPx))
        val height = max(box.height, ceil(measurer.measure(text, size, floor(width).toInt().coerceAtLeast(1)).height))
        val sized = Box(
            box.centerX - width / 2f,
            box.centerY - height / 2f,
            box.centerX + width / 2f,
            box.centerY + height / 2f,
        )
        return Fit(size, clampWidth(sized, maxWidthPx), overflowed = true)
    }

    /**
     * Keeps [box] no wider than [maxWidth] and within 0..[maxWidth] horizontally, shifting it
     * sideways rather than cutting it.
     */
    fun clampWidth(box: Box, maxWidth: Float): Box {
        if (maxWidth <= 0f) return box
        val width = min(box.width, maxWidth)
        var left = box.centerX - width / 2f
        left = left.coerceIn(0f, maxWidth - width)
        return Box(left, box.top, left + width, box.bottom)
    }
}
