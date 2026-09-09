package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.domain.chapter.model.ChapterBookmarkWithChapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/**
 * Every bookmarked spot across a series, so older ones stay findable without opening the chapter
 * that happens to hold them.
 */
@Composable
fun MangaBookmarksDialog(
    bookmarks: List<ChapterBookmarkWithChapter>,
    onDismissRequest: () -> Unit,
    onJumpTo: (ChapterBookmarkWithChapter) -> Unit,
    onDelete: (ChapterBookmarkWithChapter) -> Unit,
) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(MR.strings.action_chapter_bookmarks),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )

            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.information_no_manga_bookmarks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(bookmarks, key = { it.bookmark.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpTo(entry) }
                                .padding(
                                    start = MaterialTheme.padding.medium,
                                    end = MaterialTheme.padding.small,
                                    top = MaterialTheme.padding.small,
                                    bottom = MaterialTheme.padding.small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.chapterName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        MR.strings.chapter_bookmark_entry,
                                        entry.bookmark.pageIndex + 1,
                                        numberFormat.format(entry.bookmark.pageOffset),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                entry.bookmark.note?.takeIf { it.isNotBlank() }?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(entry) }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Delete,
                                    contentDescription = stringResource(MR.strings.action_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
