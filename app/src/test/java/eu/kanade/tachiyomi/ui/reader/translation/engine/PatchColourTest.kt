package eu.kanade.tachiyomi.ui.reader.translation.engine

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PatchColourTest {

    private val navy = 0xFF1E2A4A.toInt()
    private val cream = 0xFFEDE6D6.toInt()

    @Test
    fun `a flat ring gives its own colour and full uniformity`() {
        val ring = IntArray(200) { cream }
        PatchColour.sample(ring) shouldBe PatchColour.Sample(cream, 1f)
    }

    @Test
    fun `the median ignores a few stray lettering pixels`() {
        val ring = IntArray(100) { if (it % 10 == 0) PatchColour.BLACK else PatchColour.WHITE }
        val sample = PatchColour.sample(ring)
        sample.fill shouldBe PatchColour.WHITE
        sample.uniformFraction shouldBe 0.9f
    }

    @Test
    fun `busy art is not uniform`() {
        val colours = intArrayOf(navy, cream, 0xFF6B8E23.toInt(), 0xFFB22222.toInt())
        val ring = IntArray(100) { colours[it % colours.size] }
        PatchColour.sample(ring).uniformFraction shouldBeLessThan 0.4f
    }

    @Test
    fun `only the first count pixels are sampled`() {
        val ring = IntArray(10) { if (it < 4) navy else PatchColour.WHITE }
        PatchColour.sample(ring, count = 4).fill shouldBe navy
    }

    @Test
    fun `no pixels falls back to white`() {
        PatchColour.sample(IntArray(0)) shouldBe PatchColour.Sample(PatchColour.WHITE, 0f)
    }

    @Test
    fun `lettering is black on light fills and white on dark ones`() {
        PatchColour.letteringColour(PatchColour.WHITE) shouldBe PatchColour.BLACK
        PatchColour.letteringColour(cream) shouldBe PatchColour.BLACK
        PatchColour.letteringColour(PatchColour.BLACK) shouldBe PatchColour.WHITE
        PatchColour.letteringColour(navy) shouldBe PatchColour.WHITE
    }

    @Test
    fun `lettering always meets WCAG AA contrast`() {
        for (r in 0..255 step 15) {
            for (g in 0..255 step 15) {
                for (b in 0..255 step 15) {
                    val fill = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    PatchColour.contrast(fill, PatchColour.letteringColour(fill)) shouldBeGreaterThanOrEqual 4.5
                }
            }
        }
    }

    @Test
    fun `ring pad follows line height with a minimum`() {
        PatchColour.ringPad(10f) shouldBe 3
        PatchColour.ringPad(40f) shouldBe 6
    }

    @Test
    fun `ring strips surround the box`() {
        val rects = PatchColour.ringRects(Box(10f, 10f, 20f, 20f), pad = 3, width = 100, height = 100)
        rects shouldContainExactly listOf(
            PixelRect(5, 5, 25, 7),
            PixelRect(5, 23, 25, 25),
            PixelRect(5, 7, 7, 23),
            PixelRect(23, 7, 25, 23),
        )
    }

    @Test
    fun `ring strips are clipped to the image`() {
        val rects = PatchColour.ringRects(Box(1f, 1f, 20f, 20f), pad = 3, width = 22, height = 100)
        // Top and left strips fall outside the image, the right one is cut short.
        rects shouldContainExactly listOf(PixelRect(0, 23, 22, 25))
        PatchColour.ringRects(Box(50f, 50f, 60f, 60f), pad = 3, width = 10, height = 10).shouldBeEmpty()
    }
}
