package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.ChapterBookmark
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterBookmarkRepositoryImpl(
    private val database: Database,
) : ChapterBookmarkRepository {

    override suspend fun getByChapterId(chapterId: Long): List<ChapterBookmark> {
        return try {
            database.chapterBookmarksQueries
                .getByChapterId(chapterId, ::mapChapterBookmark)
                .awaitAsList()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun insert(chapterId: Long, pageIndex: Int, pageOffset: Double, createdAt: Long) {
        try {
            database.chapterBookmarksQueries.insert(
                chapterId = chapterId,
                pageIndex = pageIndex.toLong(),
                pageOffset = pageOffset,
                createdAt = createdAt,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun delete(id: Long) {
        try {
            database.chapterBookmarksQueries.deleteById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private fun mapChapterBookmark(
        id: Long,
        chapterId: Long,
        pageIndex: Long,
        pageOffset: Double,
        createdAt: Long,
    ): ChapterBookmark = ChapterBookmark(
        id = id,
        chapterId = chapterId,
        pageIndex = pageIndex.toInt(),
        pageOffset = pageOffset,
        createdAt = createdAt,
    )
}
