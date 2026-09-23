package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Edit
import tachiyomi.domain.chapter.model.ChapterBookmark
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

@Composable
fun ReaderChapterBookmarksDialog(
    bookmarks: List<ChapterBookmark>,
    onDismissRequest: () -> Unit,
    onJumpTo: (ChapterBookmark) -> Unit,
    onDelete: (ChapterBookmark) -> Unit,
    onEditNote: (ChapterBookmark, String?) -> Unit,
) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }
    var editing by remember { mutableStateOf<ChapterBookmark?>(null) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(MR.strings.action_chapter_bookmarks),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )

            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.information_no_chapter_bookmarks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpTo(bookmark) }
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
                                    text = stringResource(
                                        MR.strings.chapter_bookmark_entry,
                                        bookmark.pageIndex + 1,
                                        numberFormat.format(bookmark.pageOffset),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                bookmark.note?.takeIf { it.isNotBlank() }?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { editing = bookmark }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Edit,
                                    contentDescription = stringResource(MR.strings.action_edit_note),
                                )
                            }
                            IconButton(onClick = { onDelete(bookmark) }) {
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

    editing?.let { bookmark ->
        BookmarkNoteDialog(
            initialNote = bookmark.note.orEmpty(),
            onConfirm = { note ->
                onEditNote(bookmark, note.takeIf { it.isNotBlank() })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BookmarkNoteDialog(
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf(initialNote) }

    AlertDialog(
        title = { Text(stringResource(MR.strings.action_edit_note)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
