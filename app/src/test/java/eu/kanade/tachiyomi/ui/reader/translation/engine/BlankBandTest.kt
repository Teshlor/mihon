package eu.kanade.tachiyomi.ui.reader.translation.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BlankBandTest {

    private class Image(val width: Int, val height: Int, fill: Int) {
        val pixels = IntArray(width * height) { fill }

        operator fun set(x: Int, y: Int, argb: Int) {
            pixels[y * width + x] = argb
        }

        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
            for (y in top..<bottom) for (x in left..<right) this[x, y] = argb
        }

        fun isBlank() = BlankBand.isBlank(width, height) { y, into ->
            System.arraycopy(pixels, y * width, into, 0, width)
        }
    }

    private fun grey(level: Int): Int {
        val l = level.coerceIn(0, 255)
        return (0xFF shl 24) or (l shl 16) or (l shl 8) or l
    }

    private val white = grey(255)
    private val black = grey(0)

    /**
     * Five glyph-like shapes 40 px tall drawn with 12 px strokes, the size of lettering on a page
     * about 800 px wide.
     */
    private fun Image.drawWord(left: Int, top: Int, ink: Int) {
        repeat(5) { i ->
            val x = left + i * 56
            fillRect(x, top, x + 12, top + 40, ink) // stem
            fillRect(x + 12, top + 14, x + 40, top + 26, ink) // bar
        }
    }

    @Test
    fun `flat white and flat black are blank`() {
        Image(800, 1000, white).isBlank() shouldBe true
        Image(800, 1000, black).isBlank() shouldBe true
    }

    @Test
    fun `a vertical gradient is blank`() {
        val image = Image(800, 1000, white)
        for (y in 0..<1000) image.fillRect(0, y, 800, y + 1, grey(y * 255 / 999))
        image.isBlank() shouldBe true
    }

    @Test
    fun `soft noise is blank`() {
        val random = Random(42)
        val image = Image(800, 1000, white)
        for (y in 0..<1000) for (x in 0..<800) image[x, y] = grey(128 + random.nextInt(-10, 11))
        image.isBlank() shouldBe true
    }

    @Test
    fun `one short word on white isn't blank`() {
        val image = Image(800, 1000, white)
        image.drawWord(300, 480, black)
        image.isBlank() shouldBe false
    }

    @Test
    fun `white text on black isn't blank`() {
        val image = Image(800, 1000, black)
        image.drawWord(300, 480, white)
        image.isBlank() shouldBe false
    }

    @Test
    fun `screentone isn't blank`() {
        val image = Image(800, 1000, white)
        for (cy in 4..<1000 step 8) for (cx in 4..<800 step 8) image.fillRect(cx - 2, cy - 2, cx + 2, cy + 2, grey(60))
        image.isBlank() shouldBe false
    }
}
