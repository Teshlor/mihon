package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.model.ChapterBookmarkWithChapter
import tachiyomi.domain.chapter.repository.ChapterBookmarkRepository

@Inject
class GetChapterBookmarksByMangaId(
    private val chapterBookmarkRepository: ChapterBookmarkRepository,
) {

    suspend fun await(mangaId: Long): List<ChapterBookmarkWithChapter> {
        return chapterBookmarkRepository.getByMangaId(mangaId)
    }
}
