package eu.kanade.tachiyomi.ui.reader.translation

/**
 * Translated pages kept in memory, least recently used first out, so scrolling back to a page
 * shows its translation at once instead of reading it again. Holds text and boxes, never bitmaps,
 * so a page costs a few kilobytes.
 *
 * Main thread only.
 */
class TranslationCache(private val maxPages: Int = DEFAULT_MAX_PAGES) {

    private val pages = object : LinkedHashMap<TranslationKey, TranslatedPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationKey, TranslatedPage>): Boolean =
            size > maxPages
    }

    val size: Int get() = pages.size

    operator fun get(key: TranslationKey): TranslatedPage? = pages[key]

    operator fun set(key: TranslationKey, page: TranslatedPage) {
        pages[key] = page
    }

    fun remove(key: TranslationKey) {
        pages.remove(key)
    }

    fun clear() = pages.clear()

    companion object {
        const val DEFAULT_MAX_PAGES = 40
    }
}
