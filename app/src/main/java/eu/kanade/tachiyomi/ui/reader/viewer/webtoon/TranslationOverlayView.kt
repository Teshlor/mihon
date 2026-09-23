package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.withTranslation
import eu.kanade.tachiyomi.ui.reader.translation.OverlayBlock
import eu.kanade.tachiyomi.ui.reader.translation.engine.Box
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale

/**
 * Draws a page's translation over the page, inside the page's own view, so the English scrolls and
 * zooms with the art. Boxes are kept as fractions of the image and mapped through the page view's
 * own source-to-view mapping on every draw.
 *
 * It never asks for any height of its own, so it can't change the page's height, which scroll
 * offsets and bookmarks depend on. It doesn't take touches either.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val page: ReaderPageImageView,
) : View(context) {

    private var blocks: List<OverlayBlock> = emptyList()

    /** Blocks from this index on are fading in. */
    private var fadeFrom = 0
    private var fadeProgress = 1f
    private var animator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val point = PointF()
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Shows [blocks], fading in those from [fadeInFrom] on. The rest appear at once.
     */
    fun show(blocks: List<OverlayBlock>, fadeInFrom: Int) {
        animator?.cancel()
        animator = null
        this.blocks = blocks

        val duration = (FADE_IN_MILLIS * context.animatorDurationScale).toLong()
        if (fadeInFrom >= blocks.size || duration <= 0L) {
            fadeFrom = blocks.size
            fadeProgress = 1f
        } else {
            fadeFrom = fadeInFrom.coerceAtLeast(0)
            fadeProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                addUpdateListener {
                    fadeProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    fun clear() {
        animator?.cancel()
        animator = null
        if (blocks.isEmpty()) return
        blocks = emptyList()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Take the size given, never more: a height of our own would change the page's height.
        fun exactly(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(exactly(widthMeasureSpec), exactly(heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Finish any fade, so the page comes back fully translated.
        animator?.end()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        val sourceWidth = page.sourceWidth.toFloat()
        val sourceHeight = page.sourceHeight.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return

        blocks.forEachIndexed { index, block ->
            val alpha = ((if (index >= fadeFrom) fadeProgress else 1f) * 255).toInt()
            if (alpha <= 0) return@forEachIndexed

            if (!map(block.patchNorm, sourceWidth, sourceHeight, rect)) return@forEachIndexed
            fillPaint.color = block.fillColour
            fillPaint.alpha = alpha
            canvas.drawRoundRect(rect, block.cornerRadiusPx, block.cornerRadiusPx, fillPaint)

            if (!map(block.textNorm, sourceWidth, sourceHeight, rect)) return@forEachIndexed
            val layout = block.layout
            layout.paint.alpha = alpha
            canvas.withTranslation(
                x = rect.centerX() - layout.width / 2f,
                y = rect.centerY() - layout.height / 2f,
            ) {
                layout.draw(this)
            }
        }
    }

    /**
     * Maps [box], in fractions of the image, to this view's coordinates.
     */
    private fun map(box: Box, sourceWidth: Float, sourceHeight: Float, out: RectF): Boolean {
        page.sourceToView(box.left * sourceWidth, box.top * sourceHeight, point) ?: return false
        val left = point.x
        val top = point.y
        page.sourceToView(box.right * sourceWidth, box.bottom * sourceHeight, point) ?: return false
        out.set(left, top, point.x, point.y)
        return true
    }

    private companion object {
        const val FADE_IN_MILLIS = 150L
    }
}
