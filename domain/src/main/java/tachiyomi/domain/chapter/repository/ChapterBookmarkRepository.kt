package tachiyomi.domain.chapter.repository

import tachiyomi.domain.chapter.model.ChapterBookmark

interface ChapterBookmarkRepository {

    suspend fun getByChapterId(chapterId: Long): List<ChapterBookmark>

    suspend fun insert(chapterId: Long, pageIndex: Int, pageOffset: Double, createdAt: Long)

    suspend fun delete(id: Long)
}
