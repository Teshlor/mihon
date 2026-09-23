package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.CarriedLine
import eu.kanade.tachiyomi.ui.reader.translation.RecognizedLine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StripPlannerTest {

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        RecognizedLine(text, Box(left, top, right, bottom))

    @Test
    fun `band height follows width within limits`() {
        StripPlanner.bandHeight(400) shouldBe 800
        StripPlanner.bandHeight(800) shouldBe 1000
        StripPlanner.bandHeight(1080) shouldBe 1350
        StripPlanner.bandHeight(4000) shouldBe 2000
        StripPlanner.overlap(400) shouldBe 96
        StripPlanner.overlap(1080) shouldBe 130
    }

    @Test
    fun `short page is one band`() {
        StripPlanner.plan(800, 900) shouldContainExactly listOf(StripPlanner.Band(0, 0, 900))
    }

    @Test
    fun `empty page has no bands`() {
        StripPlanner.plan(0, 900).shouldBeEmpty()
        StripPlanner.plan(800, 0).shouldBeEmpty()
    }

    @ParameterizedTest
    @CsvSource("800,1001", "800,5000", "720,12000", "1080,1351", "400,799", "2000,30000", "800,2000")
    fun `bands cover the page with the planned overlap`(width: Int, height: Int) {
        val bands = StripPlanner.plan(width, height)
        val bandHeight = StripPlanner.bandHeight(width)
        val overlap = StripPlanner.overlap(width)

        bands.first().top shouldBe 0
        bands.last().bottom shouldBe height
        bands.forEachIndexed { i, band ->
            band.index shouldBe i
            band.height shouldBe minOf(bandHeight, height)
        }
        bands.zipWithNext().forEach { (a, b) ->
            (b.top > a.top) shouldBe true
            (a.bottom - b.top >= overlap) shouldBe true
        }
    }

    @Test
    fun `a line found in both bands is kept once, from the band where it sits farther from the edge`() {
        val upperBand = StripPlanner.Band(0, 0, 1000)
        val lowerBand = StripPlanner.Band(1, 880, 1880)
        // Centre at 945: 55 px from the upper band's bottom, 65 px from the lower band's top.
        val upperCopy = line("안녕", 100f, 930f, 300f, 960f)
        val lowerCopy = line("안녕", 101f, 931f, 301f, 959f)

        val (upper, lower) = StripPlanner.resolveSeam(listOf(upperCopy), upperBand, listOf(lowerCopy), lowerBand)

        upper.shouldBeEmpty()
        lower shouldContainExactly listOf(lowerCopy)
    }

    @Test
    fun `a line cut by the upper band's edge gives way to the lower band`() {
        val upperBand = StripPlanner.Band(0, 0, 1000)
        val lowerBand = StripPlanner.Band(1, 880, 1880)
        val cut = line("잘린", 100f, 970f, 300f, 1000f)
        val whole = line("잘린 줄", 100f, 970f, 300f, 1010f)

        val (upper, lower) = StripPlanner.resolveSeam(listOf(cut), upperBand, listOf(whole), lowerBand)

        upper.shouldBeEmpty()
        lower shouldContainExactly listOf(whole)
    }

    @Test
    fun `a line cut by the lower band's edge gives way to the upper band`() {
        val upperBand = StripPlanner.Band(0, 0, 1000)
        val lowerBand = StripPlanner.Band(1, 880, 1880)
        val whole = line("줄", 100f, 870f, 300f, 900f)
        val cut = line("줄", 100f, 880f, 300f, 900f)

        val (upper, lower) = StripPlanner.resolveSeam(listOf(whole), upperBand, listOf(cut), lowerBand)

        upper shouldContainExactly listOf(whole)
        lower.shouldBeEmpty()
    }

    @Test
    fun `lines that don't overlap are all kept`() {
        val upperBand = StripPlanner.Band(0, 0, 1000)
        val lowerBand = StripPlanner.Band(1, 880, 1880)
        val a = line("a", 100f, 900f, 300f, 930f)
        val b = line("b", 500f, 900f, 700f, 930f)

        val (upper, lower) = StripPlanner.resolveSeam(listOf(a), upperBand, listOf(b), lowerBand)

        upper shouldContainExactly listOf(a)
        lower shouldContainExactly listOf(b)
    }

    @Test
    fun `a bubble split by a seam is held back and published whole with the next band`() {
        val bands = listOf(StripPlanner.Band(0, 0, 1000), StripPlanner.Band(1, 880, 1880))
        val other = line("다른", 100f, 100f, 300f, 130f)
        val first = line("첫째 줄", 100f, 855f, 300f, 885f)
        val second = line("둘째 줄", 100f, 893f, 300f, 923f)

        val afterFirst = StripPlanner.advance(bands, 0, emptyList(), listOf(other, first, second)) {
            LineGrouper.group(it)
        }
        afterFirst.publish shouldContainExactly listOf(listOf(other))
        afterFirst.carried shouldContainExactly listOf(CarriedLine(first, 0), CarriedLine(second, 0))

        // The lower band sees the first line cut by its top edge and the second one again.
        val firstCut = line("줄", 100f, 880f, 300f, 885f)
        val secondAgain = line("둘째 줄 (again)", 100f, 893f, 300f, 923f)
        val afterSecond = StripPlanner.advance(bands, 1, afterFirst.carried, listOf(firstCut, secondAgain)) {
            LineGrouper.group(it)
        }
        afterSecond.carried.shouldBeEmpty()
        afterSecond.publish shouldHaveSize 1
        afterSecond.publish.single() shouldContainExactly listOf(first, second)
    }

    @Test
    fun `the last band publishes everything`() {
        val bands = listOf(StripPlanner.Band(0, 0, 1000))
        val a = line("a", 0f, 990f, 100f, 1000f)
        val result = StripPlanner.advance(bands, 0, emptyList(), listOf(a)) { LineGrouper.group(it) }
        result.publish shouldContainExactly listOf(listOf(a))
        result.carried.shouldBeEmpty()
    }

    @Test
    fun `lines carried from older bands skip seam resolution and keep their band`() {
        val bands = listOf(
            StripPlanner.Band(0, 0, 1000),
            StripPlanner.Band(1, 880, 1880),
            StripPlanner.Band(2, 1760, 2760),
            StripPlanner.Band(3, 2640, 3640),
        )
        val old = line("old", 100f, 2600f, 300f, 2630f)
        val newer = line("new", 100f, 2645f, 300f, 2675f)

        val result = StripPlanner.advance(bands, 2, listOf(CarriedLine(old, 0)), listOf(newer)) {
            LineGrouper.group(it)
        }

        result.publish.shouldBeEmpty()
        result.carried shouldContainExactly listOf(CarriedLine(old, 0), CarriedLine(newer, 2))
    }
}
