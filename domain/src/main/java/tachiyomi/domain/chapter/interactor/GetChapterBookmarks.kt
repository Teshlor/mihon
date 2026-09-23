package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.model.ChapterBookmark
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
class GetChapterBookmarks(
    private val chapterBookmarkRepository: ChapterBookmarkRepository,
) {

    suspend fun await(chapterId: Long): List<ChapterBookmark> {
        return chapterBookmarkRepository.getByChapterId(chapterId)
    }
}
