package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Bitmap

/**
 * Stand-in for builds without page translation (foss, or built without -Pinclude-mlkit). The
 * reader hides the Translate action and settings when [isAvailable] is false.
 */
object PageTranslation {

    @Suppress("ktlint:standard:property-naming") // Reads like the flag it is; const so callers can fold it
    const val isAvailable: Boolean = false

    val sourceLanguages: List<String> = emptyList()

    val targetLanguages: List<String> = emptyList()

    val defaultTargetLanguage: String = "en"

    fun create(context: Context): PageTranslator = NoopPageTranslator
}

private object NoopPageTranslator : PageTranslator {

    override suspend fun translate(
        image: Bitmap,
        sourceTag: String,
        targetTag: String,
        onDownloadingModel: () -> Unit,
    ): List<PageTranslator.Block> =
        throw UnsupportedOperationException("Page translation is not included in this build")

    override fun close() = Unit
}
