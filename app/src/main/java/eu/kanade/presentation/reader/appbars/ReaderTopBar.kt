package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onTranslate: (() -> Unit)?,
    /**
     * Whether translate-as-you-scroll is on, which shows Translate as a toggle. Null where
     * Translate is a one-shot action instead (the paged viewers).
     */
    translateOn: Boolean?,
    onOpenChapterBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            AppBarActions(
                actions = listOf(
                    AppBar.Action(
                        title = stringResource(
                            if (bookmarked) {
                                MR.strings.action_remove_bookmark
                            } else {
                                MR.strings.action_bookmark
                            },
                        ),
                        icon = if (bookmarked) {
                            MaterialSymbols.RoundedFilled.Bookmark
                        } else {
                            MaterialSymbols.Rounded.Bookmark
                        },
                        onClick = onToggleBookmarked,
                    ),
                ),
            )
            if (onTranslate != null && translateOn != null) {
                TranslateToggle(checked = translateOn, onClick = onTranslate)
            }
            AppBarActions(
                actions = buildList {
                    onTranslate?.takeIf { translateOn == null }?.let {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_translate_page),
                                icon = MaterialSymbols.Rounded.Translate,
                                onClick = it,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_chapter_bookmarks),
                            onClick = onOpenChapterBookmarks,
                        ),
                    )
                    onOpenInWebView?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = it,
                            ),
                        )
                    }
                    onOpenInBrowser?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_browser),
                                onClick = it,
                            ),
                        )
                    }
                    onShare?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = it,
                            ),
                        )
                    }
                },
            )
        },
    )
}

/**
 * Translate as a toggle button: when on, a filled tonal circle behind the icon, so the state
 * doesn't rest on the icon's colour alone. Announced as a switch ("Translate pages, on").
 */
@Composable
private fun TranslateToggle(checked: Boolean, onClick: () -> Unit) {
    val title = stringResource(MR.strings.action_translate_pages)
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(title) } },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .toggleable(
                    value = checked,
                    interactionSource = null,
                    indication = ripple(),
                    role = Role.Switch,
                    onValueChange = { onClick() },
                )
                .semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // Material Symbols' translate glyph has no filled form (FILL 1 draws the same
                // outline), so the tonal circle is what marks it as on.
                imageVector = MaterialSymbols.Rounded.Translate,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
            )
        }
    }
}
