package eu.kanade.tachiyomi.ui.reader.translation

/**
 * Translations of single texts, least recently used first out, so text that comes up again, such
 * as a repeated sound effect, a name or a stock phrase, isn't translated again. Holds only text.
 *
 * Main thread only.
 */
class TranslatedTextCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Key(val sourceLang: String, val targetLang: String, val text: String)

    private val entries = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>): Boolean = size > maxEntries
    }

    val size: Int get() = entries.size

    operator fun get(sourceLang: String, targetLang: String, text: String): String? =
        entries[Key(sourceLang, targetLang, text)]

    operator fun set(sourceLang: String, targetLang: String, text: String, translated: String) {
        entries[Key(sourceLang, targetLang, text)] = translated
    }

    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1000
    }
}
