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
     * What is ready to publish after a band, and what has to wait for bands still to be read.
     *
     * @param publish groups of lines, each one bubble in reading order, that no band still to be
     * read can change.
     * @param carried lines held back, unpublished, because they reach into a band still to be read
     * or belong to a bubble that does.
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
     * Folds band [bandIndex]'s lines into the page, when the bands are read top to bottom. The same
     * as [settle] with every band above this one done.
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
    ): Advance = settle(bands, bandIndex, (0..<bandIndex).toSet(), carried, found, group)

    /**
     * Folds band [bandIndex]'s lines into the page, with the bands read in any order.
     *
     * Each seam is resolved once, by whichever of its two bands is read second: every line of the
     * first one that reaches into the second is still held then, since the second wasn't read yet.
     * A bubble is published once none of its lines reach into a band that is still to be read.
     *
     * @param bands the page's bands, from [plan].
     * @param done the bands read before this one.
     * @param held the lines those bands held back, see [Advance.carried].
     * @param found the lines found in this band, already moved into page coordinates.
     * @param group groups lines into bubbles, see [LineGrouper.group].
     */
    fun settle(
        bands: List<Band>,
        bandIndex: Int,
        done: Set<Int>,
        held: List<CarriedLine>,
        found: List<RecognizedLine>,
        group: (List<RecognizedLine>) -> List<List<RecognizedLine>>,
    ): Advance {
        val band = bands[bandIndex]
        val above = bandIndex - 1
        val below = bandIndex + 1

        // Only lines from the bands either side can overlap this one.
        val fromAbove = held.filter { it.band == above }.map { it.line }
        val fromBelow = held.filter { it.band == below }.map { it.line }
        val others = held.filter { it.band != above && it.band != below }

        var keptFound = found
        var keptAbove = fromAbove
        if (above in done && fromAbove.isNotEmpty()) {
            val (upper, lower) = resolveSeam(fromAbove, bands[above], keptFound, band)
            keptAbove = upper
            keptFound = lower
        }
        var keptBelow = fromBelow
        if (below in done && fromBelow.isNotEmpty()) {
            val (upper, lower) = resolveSeam(keptFound, band, fromBelow, bands[below])
            keptFound = upper
            keptBelow = lower
        }

        val bandOf = IdentityHashMap<RecognizedLine, Int>()
        others.forEach { bandOf[it.line] = it.band }
        keptAbove.forEach { bandOf[it] = above }
        keptFound.forEach { bandOf[it] = bandIndex }
        keptBelow.forEach { bandOf[it] = below }

        val all = others.map { it.line } + keptAbove + keptFound + keptBelow
        if (all.isEmpty()) return Advance(emptyList(), emptyList())

        // A line reaching into a band still to be read might be cut, or found again there.
        val unread = bands.filter { it.index != bandIndex && it.index !in done }
        fun pending(line: RecognizedLine) = unread.any { line.box.bottom > it.top && line.box.top < it.bottom }

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
