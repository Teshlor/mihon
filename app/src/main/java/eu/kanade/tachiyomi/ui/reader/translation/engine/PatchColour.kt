package eu.kanade.tachiyomi.ui.reader.translation.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Picks the colour to paint over original lettering with, and the colour to letter the English
 * in, from a thin ring of pixels just outside the text.
 *
 * Colours are packed ARGB ints, as android.graphics.Color uses, but nothing here needs Android.
 */
object PatchColour {

    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** Ring thickness in pixels. */
    const val RING_THICKNESS = 2

    /** The ring sits at least this many pixels outside the text... */
    const val MIN_RING_PAD = 3

    /** ...or this fraction of the line height, whichever is more. */
    const val RING_PAD_TO_LINE_HEIGHT = 0.15f

    /** Per-channel distance from the median that still counts as the same colour. */
    const val UNIFORM_TOLERANCE = 28

    /**
     * Relative luminance above which black lettering contrasts more than white. Black on a fill
     * of luminance L gives (L + 0.05) / 0.05, white gives 1.05 / (L + 0.05); they are equal at
     * about 0.179. Either choice is then at least 4.58:1.
     */
    const val LUMINANCE_THRESHOLD = 0.179

    /**
     * @param fill per-channel median of the ring, fully opaque.
     * @param uniformFraction share of ring pixels within [UNIFORM_TOLERANCE] of [fill]; near 1
     * for a flat bubble, low for busy art.
     */
    data class Sample(val fill: Int, val uniformFraction: Float)

    fun ringPad(lineHeight: Float): Int = max(MIN_RING_PAD, ceil(lineHeight * RING_PAD_TO_LINE_HEIGHT).toInt())

    /**
     * The four strips (top, bottom, left, right) making up a ring [pad] pixels outside [box],
     * clipped to a [width] x [height] image. Strips entirely outside the image are left out.
     */
    fun ringRects(box: Box, pad: Int, width: Int, height: Int, thickness: Int = RING_THICKNESS): List<PixelRect> {
        val innerLeft = floor(box.left).toInt() - pad
        val innerTop = floor(box.top).toInt() - pad
        val innerRight = ceil(box.right).toInt() + pad
        val innerBottom = ceil(box.bottom).toInt() + pad
        val outerLeft = innerLeft - thickness
        val outerTop = innerTop - thickness
        val outerRight = innerRight + thickness
        val outerBottom = innerBottom + thickness

        return listOf(
            PixelRect(outerLeft, outerTop, outerRight, innerTop),
            PixelRect(outerLeft, innerBottom, outerRight, outerBottom),
            PixelRect(outerLeft, innerTop, innerLeft, innerBottom),
            PixelRect(innerRight, innerTop, outerRight, innerBottom),
        )
            .map {
                PixelRect(
                    left = it.left.coerceIn(0, width),
                    top = it.top.coerceIn(0, height),
                    right = it.right.coerceIn(0, width),
                    bottom = it.bottom.coerceIn(0, height),
                )
            }
            .filterNot { it.isEmpty }
    }

    /**
     * Per-channel median of the first [count] [pixels], and how uniform they are. White when
     * there are no pixels at all.
     */
    fun sample(pixels: IntArray, count: Int = pixels.size): Sample {
        if (count <= 0) return Sample(WHITE, 0f)
        val r = IntArray(count) { (pixels[it] shr 16) and 0xFF }
        val g = IntArray(count) { (pixels[it] shr 8) and 0xFF }
        val b = IntArray(count) { pixels[it] and 0xFF }
        val mr = median(r)
        val mg = median(g)
        val mb = median(b)
        var uniform = 0
        for (i in 0..<count) {
            val p = pixels[i]
            if (abs(((p shr 16) and 0xFF) - mr) <= UNIFORM_TOLERANCE &&
                abs(((p shr 8) and 0xFF) - mg) <= UNIFORM_TOLERANCE &&
                abs((p and 0xFF) - mb) <= UNIFORM_TOLERANCE
            ) {
                uniform++
            }
        }
        return Sample(argb(mr, mg, mb), uniform.toFloat() / count)
    }

    /**
     * Black or white, whichever contrasts more with [fill].
     */
    fun letteringColour(fill: Int): Int = if (relativeLuminance(fill) > LUMINANCE_THRESHOLD) BLACK else WHITE

    /**
     * WCAG relative luminance of an ARGB colour, 0 for black to 1 for white. Alpha is ignored.
     */
    fun relativeLuminance(colour: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((colour shr 16) and 0xFF) +
            0.7152 * channel((colour shr 8) and 0xFF) +
            0.0722 * channel(colour and 0xFF)
    }

    /**
     * WCAG contrast ratio between two colours, from 1 to 21.
     */
    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun median(values: IntArray): Int {
        values.sort()
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else ((values[mid - 1] + values[mid]) / 2f).roundToInt()
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
