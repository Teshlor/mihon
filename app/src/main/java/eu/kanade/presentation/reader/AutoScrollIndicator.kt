package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shows that auto-scroll is running, which otherwise has no visible sign at all, and doubles as a
 * quick way to change pace. Tapping it reveals three speed presets so the pace can be changed
 * without opening settings mid-chapter.
 *
 * Only the pill itself is interactive, so touches elsewhere still reach the reader and cancel
 * auto-scroll as usual.
 */
@Composable
fun AutoScrollIndicator(
    visible: Boolean,
    currentSpeed: Int,
    onSelectSpeed: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // Collapse whenever auto-scroll stops, so it never reappears already open.
    LaunchedEffect(visible) {
        if (!visible) expanded = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MaterialTheme.padding.medium),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.small,
                        vertical = MaterialTheme.padding.extraSmall,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    if (expanded) {
                        SpeedOption(
                            label = stringResource(MR.strings.auto_scroll_speed_slow),
                            speed = ReaderPreferences.AUTO_SCROLL_SPEED_SLOW,
                            currentSpeed = currentSpeed,
                            onSelectSpeed = onSelectSpeed,
                        )
                        SpeedOption(
                            label = stringResource(MR.strings.auto_scroll_speed_medium),
                            speed = ReaderPreferences.AUTO_SCROLL_SPEED_MEDIUM,
                            currentSpeed = currentSpeed,
                            onSelectSpeed = onSelectSpeed,
                        )
                        SpeedOption(
                            label = stringResource(MR.strings.auto_scroll_speed_fast),
                            speed = ReaderPreferences.AUTO_SCROLL_SPEED_FAST,
                            currentSpeed = currentSpeed,
                            onSelectSpeed = onSelectSpeed,
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = { expanded = !expanded },
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.ArrowDownward,
                            contentDescription = stringResource(MR.strings.auto_scroll_indicator),
                            modifier = Modifier.padding(MaterialTheme.padding.small),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedOption(
    label: String,
    speed: Int,
    currentSpeed: Int,
    onSelectSpeed: (Int) -> Unit,
) {
    val selected = speed == currentSpeed
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        onClick = { onSelectSpeed(speed) },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        )
    }
}
