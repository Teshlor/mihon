package eu.kanade.tachiyomi.ui.reader.translation.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LookaheadBudgetTest {

    // A 1440 x 3120 phone: the reader's usual extra space is three quarters of a screen.
    private val base = 3120 * 3 / 4

    @Test
    fun `800 px pages on a big phone get the full three screens`() {
        // 64 MiB at 4 * 800 * 800 / 1440 = 1777 bytes per pixel of list is 37765 px, capped.
        LookaheadBudget.extraSpacePx(base, 3120, 1440, 800, 256) shouldBe 9360
    }

    @Test
    fun `wide pages get what the memory budget buys`() {
        // 4 * 2000 * 2000 / 1440 = 11111 bytes per pixel of list; 64 MiB / 11111 = 6039.
        LookaheadBudget.extraSpacePx(base, 3120, 1440, 2000, 256) shouldBe 6039
    }

    @Test
    fun `never less than the reader lays out anyway`() {
        LookaheadBudget.extraSpacePx(base, 3120, 1440, 20_000, 256) shouldBe base
        LookaheadBudget.extraSpacePx(base, 3120, 1440, 800, 0) shouldBe base
        // A viewport not laid out yet.
        LookaheadBudget.extraSpacePx(base, 0, 1440, 800, 256) shouldBe base
    }

    @Test
    fun `an unknown view width gives the base`() {
        LookaheadBudget.extraSpacePx(base, 3120, 0, 800, 256) shouldBe base
    }

    @Test
    fun `a smaller heap on a smaller phone is still capped at three screens`() {
        // 128 MiB / 4 = 32 MiB at 4 * 800 * 800 / 1080 = 2370 bytes per pixel is 14157 px.
        LookaheadBudget.extraSpacePx(2400 * 3 / 4, 2400, 1080, 800, 128) shouldBe 7200
        // At 1600 px pages it's the budget that binds: 32 MiB / 9481 = 3539 px.
        LookaheadBudget.extraSpacePx(2400 * 3 / 4, 2400, 1080, 1600, 128) shouldBe 3539
    }

    @Test
    fun `before any page is decoded pages are taken to be as wide as the view`() {
        // 4 * 1440 = 5760 bytes per pixel; 64 MiB / 5760 = 11650, capped.
        LookaheadBudget.extraSpacePx(base, 3120, 1440, 0, 256) shouldBe 9360
    }
}
