package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
class AddChapterBookmark(
    private val chapterBookmarkRepository: ChapterBookmarkRepository,
) {

    suspend fun await(chapterId: Long, pageIndex: Int, pageOffset: Double, createdAt: Long) {
        chapterBookmarkRepository.insert(chapterId, pageIndex, pageOffset, createdAt)
    }
}
