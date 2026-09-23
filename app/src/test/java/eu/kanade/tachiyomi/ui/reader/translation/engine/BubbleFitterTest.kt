package eu.kanade.tachiyomi.ui.reader.translation.engine

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.ceil

class BubbleFitterTest {

    /**
     * Every character is 0.6 x the text size wide, lines are 1.2 x the size tall, and words wrap
     * greedily. A word wider than the layout is broken over as many lines as it needs, as
     * StaticLayout does.
     */
    private object FakeMeasurer : TextMeasurer {
        override fun measure(text: String, sizePx: Float, widthPx: Int): TextMeasurer.Measured {
            val advance = sizePx * 0.6f
            val words = text.split(' ').filter { it.isNotEmpty() }
            var lines = 0
            var lineWidth = -1f // -1: nothing on the current line yet
            for (word in words) {
                val w = word.length * advance
                if (w > widthPx) {
                    if (lineWidth >= 0f) lines++
                    lines += ceil(w / widthPx).toInt()
                    lineWidth = -1f
                    continue
                }
                val needed = if (lineWidth < 0f) w else lineWidth + advance + w
                if (needed <= widthPx) {
                    lineWidth = needed
                } else {
                    lines++
                    lineWidth = w
                }
            }
            if (lineWidth >= 0f) lines++
            val widest = words.maxOfOrNull { it.length * advance } ?: 0f
            return TextMeasurer.Measured(height = lines * sizePx * 1.2f, widestWord = widest)
        }
    }

    private fun fits(text: String, fit: BubbleFitter.Fit): Boolean {
        val m = FakeMeasurer.measure(text, fit.sizePx, fit.box.width.toInt())
        return m.height <= fit.box.height + 0.5f && m.widestWord <= fit.box.width.toInt()
    }

    @Test
    fun `short text fits at the starting size`() {
        val box = Box(0f, 0f, 300f, 100f)
        val fit = BubbleFitter.fit("Hi", box, lineHeightPx = 40f, floorPx = 20f, maxWidthPx = 1000f, FakeMeasurer)

        fit.sizePx shouldBe 34f // 0.85 x 40
        fit.box shouldBe box
        fit.overflowed shouldBe false
    }

    @Test
    fun `longer text shrinks one pixel at a time but not below the floor`() {
        val box = Box(0f, 0f, 200f, 80f)
        val text = "Where are you going?"
        val fit = BubbleFitter.fit(text, box, lineHeightPx = 40f, floorPx = 20f, maxWidthPx = 1000f, FakeMeasurer)

        fit.sizePx shouldBeLessThanOrEqual 33f
        fit.sizePx shouldBeGreaterThanOrEqual 20f
        fit.box shouldBe box
        fits(text, fit) shouldBe true
        fits(text, fit.copy(sizePx = fit.sizePx + 1f)) shouldBe false
    }

    @Test
    fun `at the floor the box grows about its centre`() {
        val box = Box(100f, 100f, 170f, 140f)
        val text = "I'll be right back, Mother"
        val fit = BubbleFitter.fit(text, box, lineHeightPx = 30f, floorPx = 12f, maxWidthPx = 1000f, FakeMeasurer)

        fit.sizePx shouldBe 12f
        fit.box.centerX shouldBe (box.centerX plusOrMinus 0.01f)
        fit.box.centerY shouldBe (box.centerY plusOrMinus 0.01f)
        (fit.box.width > box.width) shouldBe true
        (fit.box.width <= box.width * BubbleFitter.GROW_LIMIT + 0.01f) shouldBe true
        fit.overflowed shouldBe false
        fits(text, fit) shouldBe true
    }

    @Test
    fun `text is never cut off however little room there is`() {
        val box = Box(10f, 10f, 40f, 20f)
        val text = "This is a very long translation for a tiny bubble with a lot to say about everything"
        val fit = BubbleFitter.fit(text, box, lineHeightPx = 10f, floorPx = 14f, maxWidthPx = 400f, FakeMeasurer)

        fit.sizePx shouldBe 14f
        fit.overflowed shouldBe true
        fits(text, fit) shouldBe true
        fit.box.left shouldBeGreaterThanOrEqual 0f
        fit.box.right shouldBeLessThanOrEqual 400f
    }

    @Test
    fun `a word wider than the page still gets a box tall enough for all of it`() {
        val box = Box(10f, 10f, 40f, 20f)
        val text = "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaah"
        val fit = BubbleFitter.fit(text, box, lineHeightPx = 10f, floorPx = 14f, maxWidthPx = 200f, FakeMeasurer)

        fit.overflowed shouldBe true
        fit.box.width shouldBeLessThanOrEqual 200f
        val measured = FakeMeasurer.measure(text, fit.sizePx, fit.box.width.toInt())
        fit.box.height shouldBeGreaterThanOrEqual measured.height
    }

    @Test
    fun `starting size is never below the floor`() {
        val box = Box(0f, 0f, 500f, 100f)
        val fit = BubbleFitter.fit("ok", box, lineHeightPx = 5f, floorPx = 30f, maxWidthPx = 1000f, FakeMeasurer)
        fit.sizePx shouldBe 30f
    }

    @Test
    fun `clamping keeps the box on the page`() {
        BubbleFitter.clampWidth(Box(-50f, 0f, 50f, 10f), 400f) shouldBe Box(0f, 0f, 100f, 10f)
        BubbleFitter.clampWidth(Box(350f, 0f, 450f, 10f), 400f) shouldBe Box(300f, 0f, 400f, 10f)
        BubbleFitter.clampWidth(Box(-100f, 0f, 600f, 10f), 400f) shouldBe Box(0f, 0f, 400f, 10f)
    }
}
