package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.RecognizedLine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LineGrouperTest {

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        RecognizedLine(text, Box(left, top, right, bottom))

    @Test
    fun `stacked centred lines are one bubble`() {
        val a = line("어디", 200f, 100f, 400f, 130f)
        val b = line("가는 거야?", 180f, 136f, 420f, 166f)

        LineGrouper.group(listOf(b, a)) shouldContainExactly listOf(listOf(a, b))
    }

    @Test
    fun `a wide vertical gap splits bubbles`() {
        val a = line("one", 200f, 100f, 400f, 130f)
        val b = line("two", 200f, 160f, 400f, 190f) // gap 30 > 0.8 x 30

        LineGrouper.group(listOf(a, b)) shouldHaveSize 2
    }

    @Test
    fun `lines far apart sideways are different bubbles`() {
        val left = line("left", 50f, 100f, 250f, 130f)
        val right = line("right", 600f, 105f, 800f, 135f)

        LineGrouper.group(listOf(right, left)) shouldContainExactly listOf(listOf(left), listOf(right))
    }

    @Test
    fun `very different line heights are not merged`() {
        val caption = line("CAPTION", 100f, 100f, 500f, 160f)
        val small = line("small", 150f, 165f, 450f, 190f)

        LineGrouper.shouldMerge(caption.box, small.box) shouldBe false
        LineGrouper.group(listOf(caption, small)) shouldHaveSize 2
    }

    @Test
    fun `grouping is transitive through a middle line`() {
        val a = line("a", 200f, 100f, 400f, 130f)
        val b = line("b", 220f, 135f, 420f, 165f)
        val c = line("c", 240f, 170f, 440f, 200f)

        LineGrouper.shouldMerge(a.box, c.box) shouldBe false
        LineGrouper.group(listOf(c, a, b)) shouldContainExactly listOf(listOf(a, b, c))
    }

    @Test
    fun `lines on one row read left to right`() {
        val right = line("world", 300f, 102f, 400f, 130f)
        val left = line("hello", 190f, 100f, 290f, 128f)
        val below = line("again", 200f, 135f, 380f, 163f)

        LineGrouper.readingOrder(listOf(below, right, left)) shouldContainExactly listOf(left, right, below)
    }

    @Test
    fun `bubbles are ordered top to bottom`() {
        val lower = line("lower", 100f, 500f, 300f, 530f)
        val upper = line("upper", 100f, 100f, 300f, 130f)

        LineGrouper.group(listOf(lower, upper)).map { it.single() } shouldContainExactly listOf(upper, lower)
    }

    @Test
    fun `no lines, no bubbles`() {
        LineGrouper.group(emptyList()).shouldBeEmpty()
    }

    @Test
    fun `korean joins with spaces, japanese and chinese without`() {
        val lines = listOf(line(" 잠깐 ", 0f, 0f, 1f, 1f), line("나갔다 올게", 0f, 0f, 1f, 1f))
        LineGrouper.joinText(lines, "ko") shouldBe "잠깐 나갔다 올게"
        LineGrouper.joinText(lines.map { it.copy(text = "ちょっと") }, "ja") shouldBe "ちょっとちょっと"
        LineGrouper.joinText(lines.map { it.copy(text = "你好") }, "zh") shouldBe "你好你好"
    }

    @Test
    fun `median height`() {
        LineGrouper.medianHeight(emptyList()) shouldBe 0f
        LineGrouper.medianHeight(
            listOf(line("", 0f, 0f, 1f, 10f), line("", 0f, 0f, 1f, 30f), line("", 0f, 0f, 1f, 20f)),
        ) shouldBe 20f
        LineGrouper.medianHeight(listOf(line("", 0f, 0f, 1f, 10f), line("", 0f, 0f, 1f, 20f))) shouldBe 15f
    }
}
