package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.CarriedLine
import eu.kanade.tachiyomi.ui.reader.translation.RecognizedLine
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Splits a tall page into overlapping horizontal bands for text recognition, and stitches the
 * lines found in each band back together.
 *
 * Text recognition works best on glyphs of about 16-24 px and at a sensible aspect ratio, and
 * webtoon pages run to 10,000 px and more, so each band is read at full width on its own. Bands
 * overlap so that every line of text fits whole in at least one band; the copies found in both
 * bands of a seam are then reduced to one.
 */
object StripPlanner {

    const val MIN_BAND_HEIGHT = 800
    const val MAX_BAND_HEIGHT = 2000
    const val BAND_HEIGHT_TO_WIDTH = 1.25f
    const val OVERLAP_TO_WIDTH = 0.12f
    const val MIN_OVERLAP = 96

    /** A line within this many pixels of a band's edge is treated as cut by it. */
    const val EDGE_TOLERANCE = 2f

    /** Lines from either side of a seam that overlap at least this much are the same line. */
    const val SEAM_IOU = 0.5f

    /**
     * Rows [top, bottom) of the page.
     */
    data class Band(val index: Int, val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    /**
     * What is ready to publish after a band, and what has to wait for the next one.
     *
     * @param publish groups of lines, each one bubble in reading order, that no later band can
     * change.
     * @param carried lines held for the next band, because they reach into it or belong to a
     * bubble that does.
     */
    data class Advance(val publish: List<List<RecognizedLine>>, val carried: List<CarriedLine>)

    fun bandHeight(width: Int): Int =
        (width * BAND_HEIGHT_TO_WIDTH).roundToInt().coerceIn(MIN_BAND_HEIGHT, MAX_BAND_HEIGHT)

    fun overlap(width: Int): Int =
        max((width * OVERLAP_TO_WIDTH).roundToInt(), MIN_OVERLAP).coerceAtMost(bandHeight(width) / 2)

    /**
     * Bands covering a [width] x [height] page top to bottom. Consecutive bands overlap by at
     * least [overlap]. The last band is aligned to the bottom of the page rather than cut short,
     * so no band is ever a thin sliver.
     */
    fun plan(width: Int, height: Int): List<Band> {
        if (width <= 0 || height <= 0) return emptyList()
        val bandHeight = bandHeight(width)
        if (height <= bandHeight) return listOf(Band(0, 0, height))

        val step = bandHeight - overlap(width)
        val bands = mutableListOf<Band>()
        var top = 0
        while (true) {
            if (top + bandHeight >= height) {
                bands += Band(bands.size, height - bandHeight, height)
                break
            }
            bands += Band(bands.size, top, top + bandHeight)
            top += step
        }
        return bands
    }

    /**
     * Removes the duplicates the overlap between [upperBand] and [lowerBand] produces. [upper] and
     * [lower] are the lines found in each band, in page coordinates.
     *
     * - Two lines with IoU >= [SEAM_IOU] are one line: the copy whose centre is farther from its
     *   own band's edge is kept, since it is the least likely to be cut.
     * - A line touching the seam edge of its band (within [EDGE_TOLERANCE]) is cut, so it is
     *   dropped when the other band has anything overlapping it. When both copies are cut, which
     *   only happens for a line taller than the overlap, the taller one is kept.
     *
     * Returns the surviving upper and lower lines, in their original order.
     */
    fun resolveSeam(
        upper: List<RecognizedLine>,
        upperBand: Band,
        lower: List<RecognizedLine>,
        lowerBand: Band,
    ): Pair<List<RecognizedLine>, List<RecognizedLine>> {
        val dropUpper = BooleanArray(upper.size)
        val dropLower = BooleanArray(lower.size)

        fun upperCut(line: RecognizedLine) = line.box.bottom >= upperBand.bottom - EDGE_TOLERANCE
        fun lowerCut(line: RecognizedLine) = line.box.top <= lowerBand.top + EDGE_TOLERANCE

        upper.forEachIndexed { i, u ->
            lower.forEachIndexed { j, l ->
                if (!u.box.intersects(l.box)) return@forEachIndexed
                val uCut = upperCut(u)
                val lCut = lowerCut(l)
                when {
                    uCut && lCut -> if (u.box.height >= l.box.height) dropLower[j] = true else dropUpper[i] = true
                    uCut -> dropUpper[i] = true
                    lCut -> dropLower[j] = true
                    u.box.iou(l.box) >= SEAM_IOU -> {
                        val uDistance = upperBand.bottom - u.box.centerY
                        val lDistance = l.box.centerY - lowerBand.top
                        if (uDistance >= lDistance) dropLower[j] = true else dropUpper[i] = true
                    }
                }
            }
        }
        return upper.filterIndexed { i, _ -> !dropUpper[i] } to lower.filterIndexed { j, _ -> !dropLower[j] }
    }

    /**
     * Folds band [bandIndex]'s lines into the page.
     *
     * @param bands the page's bands, from [plan].
     * @param carried what the previous call held back.
     * @param found the lines found in this band, already moved into page coordinates.
     * @param group groups lines into bubbles, see [LineGrouper.group].
     */
    fun advance(
        bands: List<Band>,
        bandIndex: Int,
        carried: List<CarriedLine>,
        found: List<RecognizedLine>,
        group: (List<RecognizedLine>) -> List<List<RecognizedLine>>,
    ): Advance {
        val band = bands[bandIndex]
        val previous = bands.getOrNull(bandIndex - 1)
        val next = bands.getOrNull(bandIndex + 1)

        // Only lines from the band just above can overlap this one.
        val fromPrevious = carried.filter { it.band == bandIndex - 1 }
        val older = carried.filter { it.band != bandIndex - 1 }
        val (keptPrevious, keptFound) = if (previous != null && fromPrevious.isNotEmpty()) {
            resolveSeam(fromPrevious.map { it.line }, previous, found, band)
        } else {
            fromPrevious.map { it.line } to found
        }

        val bandOf = IdentityHashMap<RecognizedLine, Int>()
        older.forEach { bandOf[it.line] = it.band }
        keptPrevious.forEach { bandOf[it] = bandIndex - 1 }
        keptFound.forEach { bandOf[it] = bandIndex }

        val all = older.map { it.line } + keptPrevious + keptFound
        if (all.isEmpty()) return Advance(emptyList(), emptyList())

        // A line reaching into the next band might be cut, or found again there.
        fun pending(line: RecognizedLine) = next != null && line.box.bottom > next.top

        val publish = mutableListOf<List<RecognizedLine>>()
        val hold = mutableListOf<CarriedLine>()
        for (bubble in group(all)) {
            if (bubble.any(::pending)) {
                bubble.forEach { hold += CarriedLine(it, bandOf.getValue(it)) }
            } else {
                publish += bubble
            }
        }
        return Advance(publish, hold)
    }
}
