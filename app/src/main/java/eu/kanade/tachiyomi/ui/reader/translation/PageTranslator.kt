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

    /**
     * Makes sure the text recognition model for [sourceTag] and the translation models from
     * [sourceTag] to [targetTag] are on the phone, downloading any that are missing. Call it once
     * per language pair before [recognize] and [translateText].
     *
     * @param onDownloadingModel called before a model has to be downloaded, which can take a while.
     * @throws TranslationNeedsWifiException if a model has to be downloaded and the phone is not
     * on Wi-Fi.
     */
    suspend fun ensureModels(sourceTag: String, targetTag: String, onDownloadingModel: () -> Unit)

    /**
     * Finds the lines of text in [image], with their boxes in [image]'s pixels. The models must
     * already be there, see [ensureModels].
     */
    suspend fun recognize(image: Bitmap, sourceTag: String): List<RecognizedLine>

    /**
     * Translates each of [texts], returning one result per text in the same order. Texts are
     * returned unchanged when the two languages are the same or can't be translated between. The
     * models must already be there, see [ensureModels].
     */
    suspend fun translateText(texts: List<String>, sourceTag: String, targetTag: String): List<String>
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
