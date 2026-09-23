package eu.kanade.tachiyomi.ui.reader.translation.engine

/**
 * Which band of which page is read, and translated, next. Lower is sooner.
 *
 * Bands are ranked by where their bottom edge is on screen against a read line a quarter of the
 * way down: the band the reader is about to read first, then the ones below it in the order they
 * scroll in, whatever page they belong to. Bands already above the read line come after every band
 * ahead, nearest first, and bands of pages that aren't on screen at all come last.
 */
object BandPriority {

    /** Where the reader is taken to be reading, as a fraction of the viewport's height. */
    const val READ_LINE = 0.25f

    /** Added to the priority of a band whose bottom is above the read line. */
    const val BEHIND = 1e7f

    /** Priority of a band of a page that isn't attached to the list. */
    const val DETACHED = 1e8f

    /**
     * Priority of a band whose bottom edge is [bandBottomPx] from the top of a viewport
     * [viewportHeight] pixels tall.
     */
    fun of(bandBottomPx: Float, viewportHeight: Int): Float {
        val readLine = viewportHeight * READ_LINE
        return if (bandBottomPx > readLine) bandBottomPx - readLine else BEHIND + (readLine - bandBottomPx)
    }
}
