package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.RecognizedLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups recognized lines into bubbles, so each bubble is translated as one sentence rather than
 * line by line. The recognizer's own blocks can split one bubble or merge two neighbours, so this
 * works from lines.
 *
 * This is the distance-only grouping of the first milestone. Merging by shared bubble interior
 * and vertical Japanese come later.
 */
object LineGrouper {

    /** Largest vertical gap between two lines of one bubble, in line heights. */
    const val MAX_GAP = 0.8f

    /** Largest horizontal offset between the centres of two lines of one bubble, in line heights. */
    const val MAX_CENTRE_OFFSET = 1.5f

    /** Lines whose heights differ by this factor or more are different bubbles (or captions). */
    const val MAX_HEIGHT_RATIO = 1.8f

    private val NO_SPACE_LANGUAGES = setOf("ja", "zh")

    /**
     * Whether [a] and [b] read as consecutive lines of the same bubble. Distances are measured in
     * the mean height of the two lines.
     */
    fun shouldMerge(a: Box, b: Box): Boolean {
        val minHeight = min(a.height, b.height)
        val maxHeight = max(a.height, b.height)
        if (minHeight <= 0f || maxHeight / minHeight >= MAX_HEIGHT_RATIO) return false

        val lineHeight = (a.height + b.height) / 2f
        val gap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (gap > MAX_GAP * lineHeight) return false

        return abs(a.centerX - b.centerX) <= MAX_CENTRE_OFFSET * lineHeight
    }

    /**
     * Groups [lines] into bubbles. A line joins a bubble when it [shouldMerge] with any line
     * already in it. Each bubble's lines are in reading order, and bubbles are ordered by their top
     * edge.
     */
    fun group(lines: List<RecognizedLine>): List<List<RecognizedLine>> {
        if (lines.isEmpty()) return emptyList()

        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        for (i in lines.indices) {
            for (j in i + 1..lines.lastIndex) {
                if (shouldMerge(lines[i].box, lines[j].box)) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }

        return lines.indices
            .groupBy(::find)
            .values
            .map { indices -> readingOrder(indices.map(lines::get)) }
            .sortedWith(compareBy({ group -> group.minOf { it.box.top } }, { group -> group.minOf { it.box.left } }))
    }

    /**
     * Sorts one bubble's lines top to bottom, and left to right within a row. Lines whose tops are
     * within half a line height of the row's first line share its row.
     */
    fun readingOrder(lines: List<RecognizedLine>): List<RecognizedLine> {
        if (lines.size < 2) return lines
        val byTop = lines.sortedBy { it.box.top }
        val result = ArrayList<RecognizedLine>(lines.size)
        var row = mutableListOf(byTop.first())
        for (line in byTop.drop(1)) {
            val rowStart = row.first().box
            if (line.box.top - rowStart.top < rowStart.height / 2f) {
                row += line
            } else {
                result += row.sortedBy { it.box.left }
                row = mutableListOf(line)
            }
        }
        result += row.sortedBy { it.box.left }
        return result
    }

    /**
     * The bubble's text as one string: lines joined with a space, or with nothing for Japanese and
     * Chinese, which don't use spaces between words.
     */
    fun joinText(lines: List<RecognizedLine>, sourceTag: String): String {
        val separator = if (sourceTag in NO_SPACE_LANGUAGES) "" else " "
        return lines.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(separator)
    }

    /**
     * Median height of [lines], in the same units as their boxes.
     */
    fun medianHeight(lines: List<RecognizedLine>): Float {
        val heights = lines.map { it.box.height }.sorted()
        if (heights.isEmpty()) return 0f
        val mid = heights.size / 2
        return if (heights.size % 2 == 1) heights[mid] else (heights[mid - 1] + heights[mid]) / 2f
    }
}
