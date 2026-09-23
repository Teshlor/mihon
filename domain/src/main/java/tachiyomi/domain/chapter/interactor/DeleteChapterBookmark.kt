package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
class DeleteChapterBookmark(
    private val chapterBookmarkRepository: ChapterBookmarkRepository,
) {

    suspend fun await(id: Long) {
        chapterBookmarkRepository.delete(id)
    }
}
