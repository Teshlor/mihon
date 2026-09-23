package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.engine.PerfStats.Bucket
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PerfStatsTest {

    @Test
    fun `percentiles of nothing are zero`() {
        val stats = PerfStats()
        stats.p50("ocr") shouldBe 0
        stats.p90("ocr") shouldBe 0
        PerfStats.nearestRank(IntArray(0), 50) shouldBe 0
    }

    @Test
    fun `one sample is every percentile`() {
        val stats = PerfStats()
        stats.add("ocr", 42)
        stats.p50("ocr") shouldBe 42
        stats.p90("ocr") shouldBe 42
        stats.percentile("ocr", 0) shouldBe 42
        stats.percentile("ocr", 100) shouldBe 42
    }

    @Test
    fun `nearest rank on an odd count`() {
        val stats = PerfStats()
        listOf(50, 10, 40, 20, 30).forEach { stats.add("tr", it) }
        stats.p50("tr") shouldBe 30 // rank ceil(2.5) = 3
        stats.p90("tr") shouldBe 50 // rank ceil(4.5) = 5
    }

    @Test
    fun `nearest rank on an even count`() {
        val stats = PerfStats()
        (1..10).reversed().forEach { stats.add("tr", it * 10) }
        stats.p50("tr") shouldBe 50 // rank 5
        stats.p90("tr") shouldBe 90 // rank 9
        stats.percentile("tr", 91) shouldBe 100 // rank ceil(9.1) = 10
    }

    @Test
    fun `a series keeps only its latest samples`() {
        val stats = PerfStats(capacity = 4)
        listOf(1000, 1000, 1, 2, 3, 4).forEach { stats.add("copy", it) }
        stats.samples("copy") shouldBe 4
        stats.p90("copy") shouldBe 4
    }

    @Test
    fun `counters add up`() {
        val stats = PerfStats()
        stats.count("bands")
        stats.count("bands")
        stats.count("strs", 5)
        stats.counter("bands") shouldBe 2L
        stats.counter("strs") shouldBe 5L
        stats.counter("never") shouldBe 0L
    }

    @Test
    fun `reset starts a new window`() {
        val stats = PerfStats()
        stats.add("ocr", 300)
        stats.count("bands", 25)
        stats.reset()
        stats.p50("ocr") shouldBe 0
        stats.samples("ocr") shouldBe 0
        stats.counter("bands") shouldBe 0L
        stats.add("ocr", 7)
        stats.p50("ocr") shouldBe 7
    }

    @Test
    fun `bubbles are bucketed by where their top edge is on screen`() {
        PerfStats.bucketOf(null, 1000) shouldBe Bucket.GONE
        PerfStats.bucketOf(1000f, 1000) shouldBe Bucket.AHEAD
        PerfStats.bucketOf(5000f, 1000) shouldBe Bucket.AHEAD
        PerfStats.bucketOf(999.9f, 1000) shouldBe Bucket.LOWER
        PerfStats.bucketOf(500f, 1000) shouldBe Bucket.LOWER
        PerfStats.bucketOf(499.9f, 1000) shouldBe Bucket.PAST_MID
        PerfStats.bucketOf(0f, 1000) shouldBe Bucket.PAST_MID
        PerfStats.bucketOf(-3000f, 1000) shouldBe Bucket.PAST_MID
    }
}
