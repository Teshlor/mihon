package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
class UpdateChapterBookmarkNote(
    private val chapterBookmarkRepository: ChapterBookmarkRepository,
) {

    suspend fun await(id: Long, note: String?) {
        chapterBookmarkRepository.updateNote(id, note?.takeIf { it.isNotBlank() })
    }
}
