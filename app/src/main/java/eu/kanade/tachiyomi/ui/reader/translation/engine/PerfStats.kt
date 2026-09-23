package eu.kanade.tachiyomi.ui.reader.translation.engine

/**
 * Timing samples and counters for the translate-as-you-scroll performance log, see
 * [eu.kanade.tachiyomi.ui.reader.translation.TranslatePerf]. Plain Kotlin so it runs in JVM tests.
 *
 * Each named series keeps its last [capacity] samples, so a window that is never reset can't grow
 * without bound. [reset] starts a new window.
 */
class PerfStats(private val capacity: Int = DEFAULT_CAPACITY) {

    private val series = HashMap<String, Window>()
    private val counters = HashMap<String, Long>()

    private class Window(capacity: Int) {
        val values = IntArray(capacity)
        var size = 0
        var next = 0

        fun add(value: Int) {
            values[next] = value
            next = (next + 1) % values.size
            if (size < values.size) size++
        }
    }

    fun add(name: String, value: Int) {
        series.getOrPut(name) { Window(capacity) }.add(value)
    }

    fun count(name: String, by: Long = 1L) {
        counters[name] = (counters[name] ?: 0L) + by
    }

    fun counter(name: String): Long = counters[name] ?: 0L

    fun samples(name: String): Int = series[name]?.size ?: 0

    /**
     * The nearest-rank [percent] percentile of the samples of [name], 0 when there are none.
     */
    fun percentile(name: String, percent: Int): Int {
        val window = series[name] ?: return 0
        return nearestRank(window.values.copyOf(window.size), percent)
    }

    fun p50(name: String): Int = percentile(name, 50)

    fun p90(name: String): Int = percentile(name, 90)

    fun reset() {
        series.clear()
        counters.clear()
    }

    /**
     * Where a newly shown bubble was on screen, measured by its top edge.
     */
    enum class Bucket {
        /** Below the screen: shown before the reader got there. */
        AHEAD,

        /** In the lower half of the screen. */
        LOWER,

        /** In the upper half of the screen or already scrolled past: shown late. */
        PAST_MID,

        /** The page wasn't on screen at all. */
        GONE,
    }

    companion object {
        const val DEFAULT_CAPACITY = 512

        /**
         * The nearest-rank [percent] percentile of [values], 0 when empty. Sorts [values].
         */
        fun nearestRank(values: IntArray, percent: Int): Int {
            if (values.isEmpty()) return 0
            values.sort()
            val rank = ((percent.coerceIn(0, 100) * values.size) + 99) / 100
            return values[(rank - 1).coerceIn(0, values.size - 1)]
        }

        /**
         * Which [Bucket] a bubble whose top edge is [bubbleTopPx] from the top of a viewport
         * [viewportHeight] pixels tall falls in. Null means the page isn't attached.
         */
        fun bucketOf(bubbleTopPx: Float?, viewportHeight: Int): Bucket = when {
            bubbleTopPx == null -> Bucket.GONE
            bubbleTopPx >= viewportHeight -> Bucket.AHEAD
            bubbleTopPx >= viewportHeight / 2f -> Bucket.LOWER
            else -> Bucket.PAST_MID
        }
    }
}
