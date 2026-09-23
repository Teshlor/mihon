package eu.kanade.tachiyomi.ui.reader.translation.engine

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BandPriorityTest {

    // A 2000 px viewport: the read line is at 500.
    private val viewport = 2000

    private fun of(bottom: Float) = BandPriority.of(bottom, viewport)

    @Test
    fun `the band across the read line comes before the band below it`() {
        // Bands of one page 1000 px tall with 100 px overlap, the page's top at 0.
        val acrossReadLine = of(1000f)
        val below = of(1900f)
        acrossReadLine shouldBeLessThan below
        of(501f) shouldBeLessThan of(502f)
    }

    @Test
    fun `the next page's top band comes before bands further down, and after bands above it`() {
        // A tall page ends 800 px down the screen; the next page starts there.
        val currentPageLastBand = of(800f)
        val nextPageTopBand = of(800f + 1000f)
        val nextPageSecondBand = of(800f + 1900f)
        currentPageLastBand shouldBeLessThan nextPageTopBand
        nextPageTopBand shouldBeLessThan nextPageSecondBand

        val order = listOf(
            "next page, second band" to 2700f,
            "current page, last band" to 800f,
            "next page, top band" to 1800f,
        ).sortedBy { of(it.second) }.map { it.first }
        order shouldContainExactly listOf("current page, last band", "next page, top band", "next page, second band")
    }

    @Test
    fun `any band ahead comes before any band behind, and the nearest behind goes first`() {
        of(1_000_000f) shouldBeLessThan of(499f)
        of(501f) shouldBeLessThan of(500f)
        of(499f) shouldBeLessThan of(400f)
        of(400f) shouldBeLessThan of(-5000f)
        (of(500f) >= BandPriority.BEHIND) shouldBe true
    }

    @Test
    fun `bands of detached pages come last`() {
        of(1_000_000f) shouldBeLessThan BandPriority.DETACHED
        of(-1_000_000f) shouldBeLessThan BandPriority.DETACHED
    }
}
