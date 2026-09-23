package eu.kanade.tachiyomi.ui.reader.translation

import eu.kanade.tachiyomi.ui.reader.translation.engine.Box

// Plain data shared by the translate-as-you-scroll pipeline. No Android or ML Kit types, so the
// engine and its tests compile in every build and run on the JVM.

/**
 * One line of text found on a page.
 *
 * @param box where the line is, in pixels of the image that was recognized.
 * @param confidence the recognizer's confidence, 0 to 1. Whether the CJK models report anything
 * meaningful here is still to be checked on a device.
 * @param language the language the recognizer thinks the line is in, if it says.
 * @param angle rotation of the line in degrees, clockwise.
 */
data class RecognizedLine(
    val text: String,
    val box: Box,
    val confidence: Float = 1f,
    val language: String? = null,
    val angle: Float = 0f,
)

/**
 * Identifies one translated page. Anything that changes the pixels the page is read from, or the
 * languages, is part of it, so changing any of them misses the cache instead of showing a stale
 * translation.
 */
data class TranslationKey(
    val chapterId: Long,
    val pageIndex: Int,
    val image: String,
    val sourceLang: String,
    val targetLang: String,
    val pipelineVersion: Int,
    val cropBorders: Boolean,
    val dualSplit: Boolean,
    val dualSplitInvert: Boolean,
    val rotateToFit: Boolean,
    val rotateToFitInvert: Boolean,
)

/**
 * Colour the original lettering is painted over with.
 */
sealed interface BubbleFill {
    val argb: Int

    /** A flat background, safe to flood in later milestones. */
    data class Flat(override val argb: Int) : BubbleFill

    /** Busy art around the text: a solid rounded patch in this colour. */
    data class Patch(override val argb: Int) : BubbleFill
}

enum class BubbleState { Ok, Uncertain, Unreadable }

/**
 * One translated speech bubble or caption.
 *
 * @param rectNorm union of the original lines, as fractions of the page's width and height.
 * @param lineHeightNorm median height of the original lines, as a fraction of the page's width,
 * which is what the English starts out sized from.
 */
data class Bubble(
    val rectNorm: Box,
    val lineHeightNorm: Float,
    val original: String,
    val translated: String,
    val confidence: Float,
    val fill: BubbleFill,
    val state: BubbleState = BubbleState.Ok,
    val hiddenHere: Boolean = false,
)

/**
 * A line held back, unpublished, at a band seam until the band on the other side has been read, so
 * that a bubble split by the seam is grouped and translated as one.
 *
 * @param band index of the band the line was found in.
 */
data class CarriedLine(val line: RecognizedLine, val band: Int)

sealed interface PageStatus {
    /** The bands in [bandsDone] have been read and published; the rest are still to do. */
    data class Partial(val bandsDone: Set<Int>) : PageStatus

    data object Complete : PageStatus

    data class Failed(val reason: String) : PageStatus
}

/**
 * Everything known about one page's translation. Holds no bitmaps.
 *
 * @param bitmapWidth width of the decoded image the page was read from. A partial result can
 * only be resumed on an image of the same size, since bands and [carried] lines are in its pixels.
 * @param carried lines held back, unpublished, by the bands read so far.
 */
data class TranslatedPage(
    val status: PageStatus,
    val blocks: List<Bubble>,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val carried: List<CarriedLine> = emptyList(),
) {
    val isComplete: Boolean get() = status is PageStatus.Complete
}
