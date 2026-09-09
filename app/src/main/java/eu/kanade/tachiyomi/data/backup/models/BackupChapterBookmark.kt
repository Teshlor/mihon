package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A bookmarked spot inside a chapter. Nested under its chapter in the backup because chapters are
 * matched by url on restore, so the chapter's own row id is not stable across devices.
 */
@Serializable
class BackupChapterBookmark(
    @ProtoNumber(1) var pageIndex: Int = 0,
    @ProtoNumber(2) var pageOffset: Double = 0.0,
    @ProtoNumber(3) var createdAt: Long = 0,
    @ProtoNumber(4) var note: String? = null,
)

/**
 * Maps a row of chapterBookmarks.getByMangaId to its chapter url and a backup entry.
 */
val backupChapterBookmarkMapper = {
        _: Long,
        _: Long,
        pageIndex: Long,
        pageOffset: Double,
        createdAt: Long,
        note: String?,
        chapterUrl: String,
        _: String,
    ->
    chapterUrl to BackupChapterBookmark(
        pageIndex = pageIndex.toInt(),
        pageOffset = pageOffset,
        createdAt = createdAt,
        note = note,
    )
}
