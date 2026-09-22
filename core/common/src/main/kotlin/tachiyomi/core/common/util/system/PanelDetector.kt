package tachiyomi.core.common.util.system

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the panels on a comic page so the reader can step through them one at a time.
 *
 * Uses recursive XY-cut: the page is split along gutters, which are full rows or columns of
 * background colour, then each piece is split again the other way until no more gutters are
 * found. This handles the grid-like layouts most manga and comics use. Slanted gutters and art
 * that bleeds across gutters stay merged into one larger panel rather than being split wrongly.
 */
object PanelDetector {

    /**
     * A panel's bounds as fractions (0 to 1) of the page's width and height.
     */
    data class Panel(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Returns the panels in reading order, or an empty list if the page doesn't split into at
     * least two panels.
     *
     * @param luma one greyscale value (0-255) per pixel, row by row. A downscaled copy of the
     * page is plenty, and much faster.
     * @param rightToLeft whether panels side by side are read right to left, as in manga.
     */
    fun detect(luma: IntArray, width: Int, height: Int, rightToLeft: Boolean): List<Panel> {
        require(width > 0 && height > 0) { "Image must not be empty" }
        require(luma.size >= width * height) { "Not enough pixels for the given size" }

        val background = estimateBackground(luma, width, height)
        val ink = BooleanArray(width * height) { abs(luma[it] - background) > INK_THRESHOLD }

        val detector = Cutter(ink, width, height, rightToLeft)
        val boxes = mutableListOf<Box>()
        detector.cut(Box(0, 0, width, height), horizontal = true, depth = 0, out = boxes)

        val minArea = width * height * MIN_PANEL_AREA_FRACTION
        val panels = boxes.filter { it.area >= minArea }
        if (panels.size < 2) return emptyList()

        val padX = width * PANEL_PADDING_FRACTION
        val padY = height * PANEL_PADDING_FRACTION
        return panels.map {
            Panel(
                left = (max(0f, it.left - padX) / width),
                top = (max(0f, it.top - padY) / height),
                right = (min(width.toFloat(), it.right + padX) / width),
                bottom = (min(height.toFloat(), it.bottom + padY) / height),
            )
        }
    }

    /**
     * The page's background is whatever colour dominates its outer edge, usually white or black.
     */
    private fun estimateBackground(luma: IntArray, width: Int, height: Int): Int {
        val histogram = IntArray(256)
        for (x in 0..<width) {
            histogram[luma[x].coerceIn(0, 255)]++
            histogram[luma[(height - 1) * width + x].coerceIn(0, 255)]++
        }
        for (y in 0..<height) {
            histogram[luma[y * width].coerceIn(0, 255)]++
            histogram[luma[y * width + width - 1].coerceIn(0, 255)]++
        }
        return histogram.indices.maxBy { histogram[it] }
    }

    /**
     * Bounds in pixels, with [right] and [bottom] exclusive.
     */
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height
    }

    private class Cutter(
        private val ink: BooleanArray,
        private val pageWidth: Int,
        private val pageHeight: Int,
        private val rightToLeft: Boolean,
    ) {
        private val minGutterRows = max(MIN_GUTTER_PX, (pageHeight * MIN_GUTTER_FRACTION).toInt())
        private val minGutterCols = max(MIN_GUTTER_PX, (pageWidth * MIN_GUTTER_FRACTION).toInt())

        fun cut(box: Box, horizontal: Boolean, depth: Int, out: MutableList<Box>) {
            val trimmed = trim(box) ?: return
            if (depth >= MAX_DEPTH) {
                out += trimmed
                return
            }

            // Try the preferred direction first, then the other. A piece that splits neither way
            // is a panel.
            var splitHorizontally = horizontal
            var pieces = split(trimmed, splitHorizontally)
            if (pieces.size < 2) {
                splitHorizontally = !horizontal
                pieces = split(trimmed, splitHorizontally)
            }
            if (pieces.size < 2) {
                out += trimmed
                return
            }

            val ordered = if (!splitHorizontally && rightToLeft) pieces.asReversed() else pieces
            ordered.forEach { cut(it, !splitHorizontally, depth + 1, out) }
        }

        /**
         * Shrinks [box] to the rows and columns that contain ink, or returns null if it's blank.
         */
        private fun trim(box: Box): Box? {
            val rows = inkPerLine(box, horizontal = true)
            val cols = inkPerLine(box, horizontal = false)
            val rowNoise = noiseLimit(box.width)
            val colNoise = noiseLimit(box.height)

            val top = rows.indexOfFirst { it > rowNoise }
            if (top == -1) return null
            val bottom = rows.indexOfLast { it > rowNoise }
            val left = cols.indexOfFirst { it > colNoise }
            if (left == -1) return null
            val right = cols.indexOfLast { it > colNoise }

            return Box(
                left = box.left + left,
                top = box.top + top,
                right = box.left + right + 1,
                bottom = box.top + bottom + 1,
            )
        }

        /**
         * Splits [box] along every gutter wide enough to count, returning the pieces in order.
         * [horizontal] cuts produce a stack of bands; otherwise side-by-side columns.
         */
        private fun split(box: Box, horizontal: Boolean): List<Box> {
            val counts = inkPerLine(box, horizontal)
            val noise = noiseLimit(if (horizontal) box.width else box.height)
            val minGutter = if (horizontal) minGutterRows else minGutterCols

            val pieces = mutableListOf<Box>()
            var start = -1
            var blankRun = 0
            for (i in counts.indices) {
                if (counts[i] > noise) {
                    if (start == -1) {
                        start = i
                    } else if (blankRun >= minGutter) {
                        pieces += piece(box, horizontal, start, i - blankRun)
                        start = i
                    }
                    blankRun = 0
                } else if (start != -1) {
                    blankRun++
                }
            }
            if (start != -1) pieces += piece(box, horizontal, start, counts.size - blankRun)
            return pieces
        }

        private fun piece(box: Box, horizontal: Boolean, from: Int, to: Int): Box {
            return if (horizontal) {
                Box(box.left, box.top + from, box.right, box.top + to)
            } else {
                Box(box.left + from, box.top, box.left + to, box.bottom)
            }
        }

        /**
         * Counts ink pixels in each row ([horizontal]) or each column of [box].
         */
        private fun inkPerLine(box: Box, horizontal: Boolean): IntArray {
            val counts = IntArray(if (horizontal) box.height else box.width)
            for (y in box.top..<box.bottom) {
                val rowStart = y * pageWidth
                for (x in box.left..<box.right) {
                    if (ink[rowStart + x]) {
                        if (horizontal) counts[y - box.top]++ else counts[x - box.left]++
                    }
                }
            }
            return counts
        }

        /**
         * Lines with this many ink pixels or fewer still count as blank, so specks of scan noise
         * or a stray sound effect tail don't hide a gutter.
         */
        private fun noiseLimit(lineLength: Int): Int = max(1, (lineLength * NOISE_FRACTION).toInt())
    }

    // How far a pixel's brightness must be from the background to count as ink.
    private const val INK_THRESHOLD = 48

    // Share of a line that may be ink while still counting as blank.
    private const val NOISE_FRACTION = 0.01f

    // Narrowest gap that counts as a gutter, as a share of the page and as an absolute minimum.
    private const val MIN_GUTTER_FRACTION = 0.008f
    private const val MIN_GUTTER_PX = 2

    // Pieces smaller than this share of the page are dropped as stray marks, not panels.
    private const val MIN_PANEL_AREA_FRACTION = 0.015f

    // Breathing room added around each panel so its border isn't clipped when zoomed in.
    private const val PANEL_PADDING_FRACTION = 0.01f

    // Real layouts rarely nest deeper than this, and it bounds the work on busy pages.
    private const val MAX_DEPTH = 5
}
