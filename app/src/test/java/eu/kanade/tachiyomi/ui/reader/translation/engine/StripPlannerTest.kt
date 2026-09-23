package eu.kanade.tachiyomi.ui.reader.translation.engine

import eu.kanade.tachiyomi.ui.reader.translation.CarriedLine
import eu.kanade.tachiyomi.ui.reader.translation.RecognizedLine
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

    // A page read band by band in any order. Each band sees the lines that reach into it, clipped
    // to it; a clipped copy is marked, so a test can tell if one is ever published.
    private fun read(page: List<RecognizedLine>, band: StripPlanner.Band): List<RecognizedLine> =
        page.filter { it.box.bottom > band.top && it.box.top < band.bottom }.map {
            val top = maxOf(it.box.top, band.top.toFloat())
            val bottom = minOf(it.box.bottom, band.bottom.toFloat())
            if (top == it.box.top && bottom == it.box.bottom) {
                it.copy()
            } else {
                it.copy(text = "${it.text}~cut", box = it.box.copy(top = top, bottom = bottom))
            }
        }

    /** Reads [page]'s bands in [order], returning each published bubble's texts. */
    private fun readInOrder(
        bands: List<StripPlanner.Band>,
        page: List<RecognizedLine>,
        order: List<Int>,
    ): List<List<String>> {
        val done = mutableSetOf<Int>()
        var held = emptyList<CarriedLine>()
        val published = mutableListOf<List<String>>()
        for (i in order) {
            val result = StripPlanner.settle(bands, i, done.toSet(), held, read(page, bands[i])) {
                LineGrouper.group(it)
            }
            published += result.publish.map { bubble -> bubble.map { it.text } }
            held = result.carried
            done += i
        }
        held.shouldBeEmpty()
        return published
    }

    private fun permutations(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
        }

    // Bands of an 800 px wide page: [0, 1000), [904, 1904), [1808, 2808), [2712, 3712).
    private val fourBands = StripPlanner.plan(800, 3712)

    private val fourBandPage = listOf(
        line("a1", 100f, 100f, 300f, 130f),
        // Whole in both bands 0 and 1.
        line("dup", 100f, 930f, 300f, 960f),
        line("b1", 100f, 1400f, 300f, 1430f),
        // One bubble across the seam of bands 1 and 2: s1 is cut by band 2, s4 by band 1, and s2
        // and s3 are whole in both.
        line("s1", 100f, 1780f, 300f, 1810f),
        line("s2", 100f, 1818f, 300f, 1848f),
        line("s3", 100f, 1856f, 300f, 1886f),
        line("s4", 100f, 1894f, 300f, 1924f),
        line("t1", 100f, 2750f, 300f, 2780f),
        line("z", 100f, 3500f, 300f, 3530f),
    )

    @Test
    fun `the test page has the bands it was drawn for`() {
        fourBands.map { it.top to it.bottom } shouldContainExactly
            listOf(0 to 1000, 904 to 1904, 1808 to 2808, 2712 to 3712)
    }

    @Test
    fun `reading bands bottom to top publishes the same bubbles as top to bottom`() {
        val bands = StripPlanner.plan(800, 2808)
        val page = fourBandPage.filter { it.box.bottom <= 2808f }

        val inOrder = readInOrder(bands, page, listOf(0, 1, 2))
        val reversed = readInOrder(bands, page, listOf(2, 1, 0))

        inOrder shouldContainExactlyInAnyOrder listOf(
            listOf("a1"),
            listOf("dup"),
            listOf("b1"),
            listOf("s1", "s2", "s3", "s4"),
            listOf("t1"),
        )
        reversed shouldContainExactlyInAnyOrder inOrder
    }

    @Test
    fun `a line touching a band's top edge is held until the band above is read`() {
        val bands = listOf(StripPlanner.Band(0, 0, 1000), StripPlanner.Band(1, 904, 1904))
        val whole = line("edge", 100f, 900f, 300f, 930f)
        val cut = line("edge~cut", 100f, 904f, 300f, 930f)

        val first = StripPlanner.settle(bands, 1, emptySet(), emptyList(), listOf(cut)) { LineGrouper.group(it) }
        first.publish.shouldBeEmpty()
        first.carried shouldContainExactly listOf(CarriedLine(cut, 1))

        val second = StripPlanner.settle(bands, 0, setOf(1), first.carried, listOf(whole)) {
            LineGrouper.group(it)
        }
        second.publish shouldContainExactly listOf(listOf(whole))
        second.carried.shouldBeEmpty()
    }

    @Test
    fun `in every order each line is published exactly once and the bubbles match reading in order`() {
        val inOrder = readInOrder(fourBands, fourBandPage, listOf(0, 1, 2, 3))
        inOrder.flatten() shouldContainExactlyInAnyOrder fourBandPage.map { it.text }

        val orders = permutations(listOf(0, 1, 2, 3))
        orders shouldHaveSize 24
        for (order in orders) {
            val published = readInOrder(fourBands, fourBandPage, order)
            withClue("order $order") {
                published.flatten() shouldContainExactlyInAnyOrder fourBandPage.map { it.text }
                published shouldContainExactlyInAnyOrder inOrder
            }
        }
    }

    @Test
    fun `nothing is held once every band is done`() {
        val done = mutableSetOf<Int>()
        var held = emptyList<CarriedLine>()
        for (i in listOf(3, 1, 0, 2)) {
            val result = StripPlanner.settle(fourBands, i, done.toSet(), held, read(fourBandPage, fourBands[i])) {
                LineGrouper.group(it)
            }
            held = result.carried
            done += i
        }
        held.shouldBeEmpty()
    }

    @Test
    fun `advance is settle with every band above done`() {
        val found = read(fourBandPage, fourBands[0])
        val advance = StripPlanner.advance(fourBands, 0, emptyList(), found) { LineGrouper.group(it) }
        val settle = StripPlanner.settle(fourBands, 0, emptySet(), emptyList(), found) { LineGrouper.group(it) }
        advance shouldBe settle
        advance.carried.map { it.line.text } shouldContainExactly listOf("dup")
    }
}
