package eu.kanade.tachiyomi.ui.reader.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslatedTextCacheTest {

    @Test
    fun `a text translated before is a hit`() {
        val cache = TranslatedTextCache()
        cache["ko", "en", "안녕"] = "Hi"
        cache["ko", "en", "안녕"] shouldBe "Hi"
    }

    @Test
    fun `other texts and other languages miss`() {
        val cache = TranslatedTextCache()
        cache["ko", "en", "안녕"] = "Hi"
        cache["ko", "en", "잘 가"] shouldBe null
        cache["ko", "fr", "안녕"] shouldBe null
        cache["ja", "en", "안녕"] shouldBe null
    }

    @Test
    fun `least recently used texts are dropped first`() {
        val cache = TranslatedTextCache(maxEntries = 2)
        cache["ko", "en", "a"] = "A"
        cache["ko", "en", "b"] = "B"
        cache["ko", "en", "a"] shouldBe "A" // a is now the most recently used
        cache["ko", "en", "c"] = "C"

        cache.size shouldBe 2
        cache["ko", "en", "b"] shouldBe null
        cache["ko", "en", "a"] shouldBe "A"
        cache["ko", "en", "c"] shouldBe "C"
    }

    @Test
    fun `it holds a thousand texts by default`() {
        val cache = TranslatedTextCache()
        repeat(1001) { cache["ko", "en", "t$it"] = "T$it" }
        cache.size shouldBe 1000
        cache["ko", "en", "t0"] shouldBe null
        cache["ko", "en", "t1000"] shouldBe "T1000"
    }
}
