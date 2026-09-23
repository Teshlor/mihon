package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.util.Locale

/**
 * Reads the text in a picture of a page and translates it, entirely on the device.
 *
 * Page translation is optional at build time, so get one from [PageTranslation.create], which
 * gives a working translator only when [PageTranslation.isAvailable]. Anything kept between pages
 * is released by [close].
 */
interface PageTranslator : Closeable {

    /**
     * One run of text, usually a speech bubble or caption, with its position in the picture.
     */
    data class Block(val bounds: Rect, val original: String, val translated: String)

    /**
     * Finds and translates the text in [image].
     *
     * @param sourceTag the language the page is written in, one of [PageTranslation.sourceLanguages].
     * @param targetTag the language to translate into, one of [PageTranslation.targetLanguages].
     * @param onDownloadingModel called before a text recognition or language model has to be
     * downloaded, which can take a while.
     * @throws TranslationNeedsWifiException if a model has to be downloaded and the phone is not
     * on Wi-Fi.
     */
    suspend fun translate(
        image: Bitmap,
        sourceTag: String,
        targetTag: String,
        onDownloadingModel: () -> Unit,
    ): List<Block>
}

/**
 * Thrown instead of starting a download when the phone is not on Wi-Fi.
 */
class TranslationNeedsWifiException : Exception("Wi-Fi is required to download a language model")

/**
 * Human-readable name for a BCP 47 tag, in the phone's language.
 */
fun languageDisplayName(tag: String): String {
    val locale = Locale.getDefault()
    return Locale.forLanguageTag(tag).getDisplayName(locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
