package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.Locale

/**
 * Reads the text in a picture of a page and translates it, entirely on the device. Text is found
 * with ML Kit's text recognition and translated with ML Kit's offline translator, which downloads
 * a language model (around 30 MB) the first time each language is used.
 *
 * Recognizers and translators are kept between pages and released by [close].
 */
class PageTranslator : Closeable {

    /**
     * One run of text, usually a speech bubble or caption, with its position in the picture.
     */
    data class Block(val bounds: Rect, val original: String, val translated: String)

    private val recognizers = mutableMapOf<String, TextRecognizer>()
    private val translators = mutableMapOf<Pair<String, String>, Translator>()

    /**
     * Finds and translates the text in [image].
     *
     * @param sourceTag the language the page is written in, one of [SOURCE_LANGUAGES].
     * @param targetTag the language to translate into, one of [targetLanguages].
     * @param onDownloadingModel called before a language model has to be downloaded, which can
     * take a while.
     */
    suspend fun translate(
        image: Bitmap,
        sourceTag: String,
        targetTag: String,
        onDownloadingModel: () -> Unit,
    ): List<Block> {
        val recognizer = recognizers.getOrPut(sourceTag) { createRecognizer(sourceTag) }
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()

        val separator = if (sourceTag in CJK_LANGUAGES) "" else " "
        val found = text.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val original = block.lines.joinToString(separator) { it.text.trim() }.trim()
            if (original.isBlank()) null else bounds to original
        }
        if (found.isEmpty()) return emptyList()

        val source = TranslateLanguage.fromLanguageTag(sourceTag)
        val target = TranslateLanguage.fromLanguageTag(targetTag)
        if (source == null || target == null || source == target) {
            return found.map { (bounds, original) -> Block(bounds, original, original) }
        }

        val translator = translators.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }
        if (!isModelDownloaded(source) || !isModelDownloaded(target)) onDownloadingModel()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

        return found.map { (bounds, original) ->
            Block(bounds, original, translator.translate(original).await())
        }
    }

    private suspend fun isModelDownloaded(language: String): Boolean {
        if (language == TranslateLanguage.ENGLISH) return true // Built in
        val models = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
        return models.any { it.language == language }
    }

    private fun createRecognizer(sourceTag: String): TextRecognizer {
        val options = when (sourceTag) {
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
        return TextRecognition.getClient(options)
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        /**
         * Languages pages can be read in. Limited to scripts ML Kit can recognize: Chinese,
         * Japanese, Korean and Latin.
         */
        val SOURCE_LANGUAGES = listOf("ja", "zh", "ko", "en", "es", "fr", "de", "it", "pt", "id", "vi")

        private val CJK_LANGUAGES = setOf("ja", "zh")

        /**
         * Every language the translator can produce.
         */
        val targetLanguages: List<String>
            get() = TranslateLanguage.getAllLanguages().sortedBy { displayName(it) }

        /**
         * The phone's language if it can be translated into, otherwise English.
         */
        val defaultTargetLanguage: String
            get() = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
                ?: TranslateLanguage.ENGLISH

        fun displayName(tag: String): String {
            val locale = Locale.getDefault()
            return Locale.forLanguageTag(tag).getDisplayName(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}
