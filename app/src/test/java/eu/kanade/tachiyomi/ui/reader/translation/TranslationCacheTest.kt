package eu.kanade.tachiyomi.ui.reader.translation

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TranslationCacheTest {

    private fun key(page: Int, source: String = "ko") = TranslationKey(
        chapterId = 1L,
        pageIndex = page,
        image = "https://example.org/$page.jpg",
        sourceLang = source,
        targetLang = "en",
        pipelineVersion = 1,
        cropBorders = false,
        dualSplit = false,
        dualSplitInvert = false,
        rotateToFit = false,
        rotateToFitInvert = false,
    )

    private val page = TranslatedPage(PageStatus.Complete, emptyList(), bitmapWidth = 800, bitmapHeight = 4000)

    @Test
    fun `changing the source language misses the cache`() {
        val cache = TranslationCache()
        cache[key(0, "ko")] = page

        key(0, "ja") shouldNotBe key(0, "ko")
        cache[key(0, "ja")].shouldBeNull()
        cache[key(0, "ko")] shouldBe page
    }

    @Test
    fun `processing settings are part of the key`() {
        key(0).copy(cropBorders = true) shouldNotBe key(0)
        key(0).copy(dualSplit = true) shouldNotBe key(0)
        key(0).copy(rotateToFit = true) shouldNotBe key(0)
        key(0).copy(pipelineVersion = 2) shouldNotBe key(0)
    }

    @Test
    fun `least recently used pages are dropped first`() {
        val cache = TranslationCache(maxPages = 3)
        cache[key(0)] = page
        cache[key(1)] = page
        cache[key(2)] = page
        cache[key(0)].shouldNotBeNull() // Touch 0, so 1 is now the oldest

        cache[key(3)] = page

        cache.size shouldBe 3
        cache[key(1)].shouldBeNull()
        cache[key(0)].shouldNotBeNull()
        cache[key(2)].shouldNotBeNull()
        cache[key(3)].shouldNotBeNull()
    }

    @Test
    fun `clear empties the cache`() {
        val cache = TranslationCache()
        cache[key(0)] = page
        cache.clear()
        cache.size shouldBe 0
    }
}
