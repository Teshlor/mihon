package tachiyomi.domain.chapter.model

/**
 * A bookmarked spot inside a chapter.
 *
 * The existing [Chapter.bookmark] flag marks a whole chapter. This marks a point within one, which
 * matters for long strip pages tall enough that a page number alone is not a useful location.
 */
data class ChapterBookmark(
    val id: Long,
    val chapterId: Long,
    val pageIndex: Int,
    /**
     * How far into [pageIndex] the spot is, as a fraction of that page's height.
     */
    val pageOffset: Double,
    val createdAt: Long,
    /**
     * Optional label. Null for spots saved without one, which is the quick one-tap path.
     */
    val note: String? = null,
)

/**
 * A bookmark together with the chapter it belongs to, for listing across a whole series.
 */
data class ChapterBookmarkWithChapter(
    val bookmark: ChapterBookmark,
    val chapterUrl: String,
    val chapterName: String,
)
