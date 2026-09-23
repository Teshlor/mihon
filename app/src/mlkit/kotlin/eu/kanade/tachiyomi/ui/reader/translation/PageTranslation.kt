package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Page translation with Google ML Kit. Only compiled in with -Pinclude-mlkit, and never in foss.
 */
object PageTranslation {

    @Suppress("ktlint:standard:property-naming") // Reads like the flag it is; const so callers can fold it
    const val isAvailable: Boolean = true

    /**
     * Languages pages can be read in. Limited to scripts ML Kit can recognize: Chinese,
     * Japanese, Korean and Latin.
     */
    val sourceLanguages: List<String> = listOf("ja", "zh", "ko", "en", "es", "fr", "de", "it", "pt", "id", "vi")

    /**
     * Every language the translator can produce.
     */
    val targetLanguages: List<String>
        get() = TranslateLanguage.getAllLanguages().sortedBy(::languageDisplayName)

    /**
     * The phone's language if it can be translated into, otherwise English.
     */
    val defaultTargetLanguage: String
        get() = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            ?: TranslateLanguage.ENGLISH

    fun create(context: Context): PageTranslator = MlKitPageTranslator(context)
}
