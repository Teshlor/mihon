package eu.kanade.tachiyomi.ui.reader.translation.engine

import kotlin.math.abs

/**
 * Spots bands with nothing to read, such as the empty gutters between a webtoon's panels, so text
 * recognition can skip them.
 *
 * Lettering is full of sharp light-dark edges; flat fills, gradients and soft noise have almost
 * none. So a band is blank when a sample of its rows shows no more than [MAX_EDGES] sharp
 * horizontal steps in brightness.
 */
object BlankBand {

    /** Every this many rows is sampled. */
    const val ROW_STEP = 2

    /** A step in brightness (0-255) between pixels two apart larger than this is an edge. */
    const val EDGE_LUMA_DELTA = 48

    /** A band with more edges than this across its sampled rows isn't blank. */
    const val MAX_EDGES = 8

    /**
     * Whether a [width] x [height] band is blank. [readRow] fills `into` (at least [width] long)
     * with row `y`'s ARGB pixels.
     */
    fun isBlank(width: Int, height: Int, readRow: (y: Int, into: IntArray) -> Unit): Boolean {
        if (width <= 2 || height <= 0) return true
        val row = IntArray(width)
        val luma = IntArray(width)
        var edges = 0
        var y = 0
        while (y < height) {
            readRow(y, row)
            for (x in 0..<width) luma[x] = luma(row[x])
            for (x in 0..<width - 2) {
                if (abs(luma[x + 2] - luma[x]) > EDGE_LUMA_DELTA) {
                    edges++
                    if (edges > MAX_EDGES) return false
                }
            }
            y += ROW_STEP
        }
        return true
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }
}
