package eu.kanade.tachiyomi.ui.reader.translation.engine

import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned rectangle. Plain Kotlin rather than android.graphics.RectF so the translation
 * engine runs in JVM unit tests. Depending on where it is used it holds bitmap pixels, view pixels
 * or fractions (0 to 1) of the page's width and height.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun union(other: Box): Box = Box(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    fun intersectionArea(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    fun intersects(other: Box): Boolean = intersectionArea(other) > 0f

    /**
     * Intersection over union, 0 when the boxes don't touch and 1 when they are identical.
     */
    fun iou(other: Box): Float {
        val inter = intersectionArea(other)
        if (inter <= 0f) return 0f
        return inter / (area + other.area - inter)
    }

    fun offset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun outset(amount: Float): Box = Box(left - amount, top - amount, right + amount, bottom + amount)

    fun scale(sx: Float, sy: Float): Box = Box(left * sx, top * sy, right * sx, bottom * sy)

    /**
     * This box grown (or shrunk) by [factor] about its centre.
     */
    fun scaleAboutCenter(factor: Float): Box {
        val halfW = width * factor / 2f
        val halfH = height * factor / 2f
        return Box(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    companion object {
        fun unionOf(boxes: Iterable<Box>): Box = boxes.reduce(Box::union)
    }
}

/**
 * Integer pixel rectangle, right and bottom exclusive.
 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}
