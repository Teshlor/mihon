package eu.kanade.tachiyomi.ui.reader.translation

import eu.kanade.tachiyomi.ui.reader.translation.PageTranslationScheduler.Companion.priorityOf
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTranslationSchedulerTest {

    @Test
    fun `the centre page comes first, then the next three, then the one behind`() {
        priorityOf(10, 10) shouldBe 0
        priorityOf(11, 10) shouldBe 1
        priorityOf(12, 10) shouldBe 2
        priorityOf(13, 10) shouldBe 3
        priorityOf(9, 10) shouldBe 4
    }

    @Test
    fun `everything else follows by distance`() {
        priorityOf(14, 10) shouldBe 14
        priorityOf(8, 10) shouldBe 12
        priorityOf(30, 10) shouldBe 30
    }

    @Test
    fun `pages sort into the planned order`() {
        val centre = 5
        (0..12).sortedBy { priorityOf(it, centre) } shouldContainExactly
            listOf(5, 6, 7, 8, 4, 3, 2, 1, 9, 0, 10, 11, 12)
    }
}
