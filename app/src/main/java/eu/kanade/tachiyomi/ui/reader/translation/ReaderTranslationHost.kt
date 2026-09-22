package eu.kanade.tachiyomi.ui.reader.translation

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates whatever the reader is currently showing and holds the result for the on-screen
 * overlay.
 *
 * It works from a screenshot of the viewer rather than the page image, so it covers every reading
 * mode and matches exactly what is on screen, including zoom, crop and split pages. The flip side
 * is that the translation only fits the current view, so it is dismissed as soon as the view
 * changes.
 */
class ReaderTranslationHost(
    private val activity: ReaderActivity,
    private val readerPreferences: ReaderPreferences,
) {

    sealed interface State {
        data object Idle : State

        /**
         * Reading and translating the page. [downloadingModel] is true while a language model is
         * downloaded for the first time, which takes much longer than a normal run.
         */
        data class Working(val downloadingModel: Boolean) : State

        /**
         * Translated text, positioned relative to the overlay view passed to [translateScreen].
         */
        data class Shown(val blocks: List<PageTranslator.Block>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val translator = PageTranslator()
    private var job: Job? = null

    /**
     * Translates what is on screen and shows it over [overlay], the view the overlay is drawn in.
     * [viewer] is the area to translate.
     */
    fun translateScreen(viewer: View, overlay: View) {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            // Let the menu slide away so it isn't captured and read as page text.
            activity.hideMenu()
            delay(MENU_HIDE_DELAY_MILLIS)

            try {
                val (screenshot, offsetX, offsetY) = capture(viewer, overlay)
                // Only shown once the screenshot is taken, so the progress indicator isn't in it.
                _state.value = State.Working(downloadingModel = false)
                val blocks = try {
                    translator.translate(
                        image = screenshot,
                        sourceTag = readerPreferences.translationSourceLanguage.get(),
                        targetTag = readerPreferences.translationTargetLanguage.get()
                            .ifEmpty { PageTranslator.defaultTargetLanguage },
                        onDownloadingModel = { _state.value = State.Working(downloadingModel = true) },
                    )
                } finally {
                    screenshot.recycle()
                }

                if (blocks.isEmpty()) {
                    activity.toast(MR.strings.translation_no_text)
                    _state.value = State.Idle
                } else {
                    _state.value = State.Shown(
                        blocks.map { it.copy(bounds = Rect(it.bounds).apply { offset(offsetX, offsetY) }) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to translate page" }
                activity.toast(MR.strings.translation_failed)
                _state.value = State.Idle
            }
        }
    }

    /**
     * Hides the translation, or stops one in progress.
     */
    fun dismiss() {
        if (_state.value == State.Idle) return
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    fun destroy() {
        dismiss()
        translator.close()
    }

    /**
     * Takes a screenshot of [viewer], and works out how far it sits from [overlay] so text found
     * in the screenshot can be drawn in the right place. Uses PixelCopy, which also captures
     * hardware-rendered content that drawing the view to a canvas would miss.
     */
    private suspend fun capture(viewer: View, overlay: View): Triple<Bitmap, Int, Int> {
        val viewerLocation = IntArray(2).also(viewer::getLocationInWindow)
        val overlayLocation = IntArray(2).also(overlay::getLocationInWindow)
        val area = Rect(
            viewerLocation[0],
            viewerLocation[1],
            viewerLocation[0] + viewer.width,
            viewerLocation[1] + viewer.height,
        )
        val bitmap = createBitmap(area.width(), area.height())

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                PixelCopy.request(
                    activity.window,
                    area,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(IllegalStateException("PixelCopy failed: $result"))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        } catch (e: Throwable) {
            // A cancelled copy may still be writing into the bitmap, so leave that one to the GC.
            if (e !is CancellationException) bitmap.recycle()
            throw e
        }

        return Triple(
            bitmap,
            viewerLocation[0] - overlayLocation[0],
            viewerLocation[1] - overlayLocation[1],
        )
    }
}

private const val MENU_HIDE_DELAY_MILLIS = 350L
