package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
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
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates pages with ML Kit. Text is found with ML Kit's text recognition, whose models Google
 * Play services downloads on first use, and translated with ML Kit's offline translator, which
 * downloads a language model (around 30 MB) the first time each language is used. Models are only
 * downloaded over Wi-Fi.
 *
 * Recognizers and translators are kept between pages and released by [close].
 */
internal class MlKitPageTranslator(private val context: Context) : PageTranslator {

    private val recognizers = mutableMapOf<String, TextRecognizer>()
    private val translators = mutableMapOf<Pair<String, String>, Translator>()

    override suspend fun translate(
        image: Bitmap,
        sourceTag: String,
        targetTag: String,
        onDownloadingModel: () -> Unit,
    ): List<PageTranslator.Block> {
        val recognizer = recognizers.getOrPut(sourceTag) { createRecognizer(sourceTag) }

        val source = TranslateLanguage.fromLanguageTag(sourceTag)
        val target = TranslateLanguage.fromLanguageTag(targetTag)
        val languages = if (source == null || target == null || source == target) null else source to target
        val translator = languages?.let {
            translators.getOrPut(it) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(it.first)
                        .setTargetLanguage(it.second)
                        .build(),
                )
            }
        }

        // Check everything that has to be downloaded up front, so the phone is never made to
        // download a model over mobile data.
        val needsDownload = !ocrModuleInstalled(recognizer) ||
            (languages != null && !(isModelDownloaded(languages.first) && isModelDownloaded(languages.second)))
        if (needsDownload) {
            if (!context.isConnectedToWifi()) throw TranslationNeedsWifiException()
            onDownloadingModel()
        }

        ensureInstalled(recognizer)
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()

        val separator = if (sourceTag in CJK_LANGUAGES) "" else " "
        val found = text.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val original = block.lines.joinToString(separator) { it.text.trim() }.trim()
            if (original.isBlank()) null else bounds to original
        }
        if (found.isEmpty()) return emptyList()

        if (translator == null) {
            return found.map { (bounds, original) -> PageTranslator.Block(bounds, original, original) }
        }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()

        return found.map { (bounds, original) ->
            PageTranslator.Block(bounds, original, translator.translate(original).await())
        }
    }

    private suspend fun ocrModuleInstalled(recognizer: TextRecognizer): Boolean {
        return ModuleInstall.getClient(context).areModulesAvailable(recognizer).await().areModulesAvailable()
    }

    /**
     * Makes sure Play services has the recognizer's model. Without this, recognition fails until
     * Play services gets round to downloading the model by itself, which may be never.
     */
    private suspend fun ensureInstalled(recognizer: TextRecognizer) {
        val client = ModuleInstall.getClient(context)
        if (client.areModulesAvailable(recognizer).await().areModulesAvailable()) return

        suspendCancellableCoroutine { continuation ->
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                    val error = when (update.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> null
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                        ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                        -> IllegalStateException(
                            "Text recognition download failed (Play services error ${update.errorCode})",
                        )
                        else -> return // Still in progress
                    }
                    client.unregisterListener(this)
                    if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { client.unregisterListener(listener) }

            val request = ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .setListener(listener)
                .build()
            client.installModules(request)
                .addOnSuccessListener {
                    if (it.areModulesAlreadyInstalled()) {
                        client.unregisterListener(listener)
                        continuation.resume(Unit)
                    }
                }
                .addOnFailureListener {
                    client.unregisterListener(listener)
                    continuation.resumeWithException(it)
                }
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

    private companion object {
        val CJK_LANGUAGES = setOf("ja", "zh")
    }
}
