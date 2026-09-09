package tachiyomi.domain.chapter.repository

import tachiyomi.domain.chapter.model.ChapterBookmark
import tachiyomi.domain.chapter.model.ChapterBookmarkWithChapter

interface ChapterBookmarkRepository {

    suspend fun getByChapterId(chapterId: Long): List<ChapterBookmark>

    suspend fun getByMangaId(mangaId: Long): List<ChapterBookmarkWithChapter>

    suspend fun insert(
        chapterId: Long,
        pageIndex: Int,
        pageOffset: Double,
        createdAt: Long,
        note: String? = null,
    )

    suspend fun updateNote(id: Long, note: String?)

    suspend fun delete(id: Long)
}
