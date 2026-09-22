package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelDetectorTest {

    private val white = 255
    private val black = 0

    /**
     * A white page with black-filled rectangles, each given as (left, top, right, bottom) with
     * right and bottom exclusive.
     */
    private fun page(width: Int, height: Int, vararg rects: IntArray, background: Int = white): IntArray {
        val ink = if (background == white) black else white
        val luma = IntArray(width * height) { background }
        for ((l, t, r, b) in rects) {
            for (y in t..<b) for (x in l..<r) luma[y * width + x] = ink
        }
        return luma
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = intArrayOf(l, t, r, b)

    @Test
    fun `single full-page image has no panels`() {
        val luma = page(100, 150, rect(5, 5, 95, 145))
        assertTrue(PanelDetector.detect(luma, 100, 150, rightToLeft = false).isEmpty())
    }

    @Test
    fun `blank page has no panels`() {
        val luma = page(100, 150)
        assertTrue(PanelDetector.detect(luma, 100, 150, rightToLeft = false).isEmpty())
    }

    @Test
    fun `stacked panels are read top to bottom`() {
        val luma = page(100, 150, rect(5, 5, 95, 70), rect(5, 80, 95, 145))
        val panels = PanelDetector.detect(luma, 100, 150, rightToLeft = false)

        assertEquals(2, panels.size)
        assertTrue(panels[0].bottom <= panels[1].top + 0.02f)
    }

    @Test
    fun `side by side panels follow reading direction`() {
        val luma = page(100, 150, rect(5, 5, 45, 145), rect(55, 5, 95, 145))

        val ltr = PanelDetector.detect(luma, 100, 150, rightToLeft = false)
        assertEquals(2, ltr.size)
        assertTrue(ltr[0].left < ltr[1].left)

        val rtl = PanelDetector.detect(luma, 100, 150, rightToLeft = true)
        assertEquals(2, rtl.size)
        assertTrue(rtl[0].left > rtl[1].left)
    }

    @Test
    fun `grid is read row by row`() {
        // Top row: one wide panel. Bottom row: two panels side by side.
        val luma = page(
            100,
            150,
            rect(5, 5, 95, 70),
            rect(5, 80, 45, 145),
            rect(55, 80, 95, 145),
        )
        val panels = PanelDetector.detect(luma, 100, 150, rightToLeft = true)

        assertEquals(3, panels.size)
        // Wide panel first, then the right-hand one, then the left-hand one.
        assertTrue(panels[0].right - panels[0].left > 0.8f)
        assertTrue(panels[1].left > 0.5f)
        assertTrue(panels[2].right < 0.5f)
    }

    @Test
    fun `black pages with white panels are handled`() {
        val luma = page(100, 150, rect(5, 5, 95, 70), rect(5, 80, 95, 145), background = black)
        assertEquals(2, PanelDetector.detect(luma, 100, 150, rightToLeft = false).size)
    }

    @Test
    fun `tiny specks are not treated as panels`() {
        val luma = page(
            100,
            150,
            rect(5, 5, 95, 70),
            rect(5, 80, 95, 145),
            rect(48, 73, 50, 75),
        )
        assertEquals(2, PanelDetector.detect(luma, 100, 150, rightToLeft = false).size)
    }

    @Test
    fun `panels are within page bounds`() {
        val luma = page(100, 150, rect(1, 1, 99, 70), rect(1, 80, 99, 149))
        val panels = PanelDetector.detect(luma, 100, 150, rightToLeft = false)
        assertEquals(2, panels.size)
        panels.forEach {
            assertTrue(it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f)
        }
    }
}
