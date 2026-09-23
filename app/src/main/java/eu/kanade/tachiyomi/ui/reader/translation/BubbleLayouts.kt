package eu.kanade.tachiyomi.ui.reader.translation

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import eu.kanade.tachiyomi.ui.reader.translation.engine.Box
import eu.kanade.tachiyomi.ui.reader.translation.engine.BubbleFitter
import eu.kanade.tachiyomi.ui.reader.translation.engine.PatchColour
import eu.kanade.tachiyomi.ui.reader.translation.engine.TextMeasurer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A translated bubble laid out for drawing on a page of a given width.
 *
 * @param patchNorm the patch painted over the original lettering, as fractions of the page's
 * width and height.
 * @param textNorm where [layout] is centred, in the same units.
 * @param layout the English, laid out in unzoomed view pixels.
 */
class OverlayBlock(
    val bubble: Bubble,
    val patchNorm: Box,
    val textNorm: Box,
    val layout: StaticLayout,
    val fillColour: Int,
    val cornerRadiusPx: Float,
)

/**
 * Lays translated bubbles out for [TranslationOverlayView][eu.kanade.tachiyomi.ui.reader.viewer.webtoon.TranslationOverlayView]:
 * fits the English into the space the original took with [BubbleFitter] and builds the
 * StaticLayouts. Safe to call off the main thread.
 */
class BubbleLayouts(context: Context) {

    private val metrics = context.resources.displayMetrics
    private val floorPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FLOOR_SP, metrics)
    private val maxCornerPx = CORNER_DP * metrics.density
    private val typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /**
     * Lays [bubbles] out for a page [viewWidth] pixels wide, whose image is [bitmapWidth] x
     * [bitmapHeight].
     */
    fun fit(bubbles: List<Bubble>, viewWidth: Int, bitmapWidth: Int, bitmapHeight: Int): List<OverlayBlock> {
        if (viewWidth <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()
        val scale = viewWidth.toFloat() / bitmapWidth
        val viewHeight = bitmapHeight * scale
        val measurePaint = newPaint(PatchColour.BLACK)
        val measurer = StaticLayoutMeasurer(measurePaint)

        return bubbles.filterNot { it.hiddenHere }.map { bubble ->
            val lineHeightPx = bubble.lineHeightNorm * viewWidth
            val original = bubble.rectNorm.scale(viewWidth.toFloat(), viewHeight)
            // Cover the glyphs' anti-aliased edges, up to where the patch colour was sampled.
            val textBox = original.outset(max(MIN_PAD_PX, lineHeightPx * PAD_TO_LINE_HEIGHT))

            val fit = BubbleFitter.fit(
                text = bubble.translated,
                box = textBox,
                lineHeightPx = lineHeightPx,
                floorPx = floorPx,
                maxWidthPx = viewWidth.toFloat(),
                measurer = measurer,
            )
            if (fit.overflowed) {
                logcat(LogPriority.DEBUG) { "Translation needed more room than its bubble: ${bubble.translated}" }
            }

            val paint = newPaint(PatchColour.letteringColour(bubble.fill.argb)).apply { textSize = fit.sizePx }
            val layout = buildLayout(bubble.translated, paint, floor(fit.box.width).toInt().coerceAtLeast(1))
            val patch = textBox.union(fit.box)
            OverlayBlock(
                bubble = bubble,
                patchNorm = patch.scale(1f / viewWidth, 1f / viewHeight),
                textNorm = fit.box.scale(1f / viewWidth, 1f / viewHeight),
                layout = layout,
                fillColour = bubble.fill.argb,
                cornerRadiusPx = min(maxCornerPx, min(patch.width, patch.height) * CORNER_TO_SIZE),
            )
        }
    }

    private fun newPaint(colour: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        typeface = this@BubbleLayouts.typeface
    }

    private class StaticLayoutMeasurer(private val paint: TextPaint) : TextMeasurer {
        override fun measure(text: String, sizePx: Float, widthPx: Int): TextMeasurer.Measured {
            paint.textSize = sizePx
            val layout = buildLayout(text, paint, widthPx)
            val widestWord = text.split(WHITESPACE).maxOfOrNull { paint.measureText(it) } ?: 0f
            return TextMeasurer.Measured(height = layout.height.toFloat(), widestWord = ceil(widestWord))
        }
    }

    private companion object {
        const val FLOOR_SP = 11f
        const val CORNER_DP = 8f
        const val CORNER_TO_SIZE = 0.3f
        const val PAD_TO_LINE_HEIGHT = 0.15f
        const val MIN_PAD_PX = 2f
        val WHITESPACE = Regex("\\s+")

        fun buildLayout(text: String, paint: TextPaint, widthPx: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .build()
    }
}
