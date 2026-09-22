package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.reader.translation.PageTranslator
import eu.kanade.tachiyomi.ui.reader.translation.ReaderTranslationHost
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shows translated text over the speech bubbles it came from. While shown, it covers the page and
 * any tap hides it, since the text only lines up with the page as it was when translated.
 */
@Composable
fun PageTranslationOverlay(
    state: ReaderTranslationHost.State,
    onDismiss: () -> Unit,
) {
    when (state) {
        ReaderTranslationHost.State.Idle -> {}
        is ReaderTranslationHost.State.Working -> WorkingIndicator(state.downloadingModel)
        is ReaderTranslationHost.State.Shown -> TranslatedBlocks(state.blocks, onDismiss)
    }
}

@Composable
private fun WorkingIndicator(downloadingModel: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(MaterialTheme.padding.large),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        if (downloadingModel) {
                            MR.strings.translation_downloading_model
                        } else {
                            MR.strings.translation_in_progress
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun TranslatedBlocks(
    blocks: List<PageTranslator.Block>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        blocks.forEach { block ->
            val bounds = block.bounds
            with(density) {
                BasicText(
                    text = block.translated,
                    style = TextStyle(color = Color.Black, textAlign = TextAlign.Center),
                    autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 20.sp),
                    modifier = Modifier
                        .offset { IntOffset(bounds.left, bounds.top) }
                        .size(bounds.width().toDp(), bounds.height().toDp())
                        .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(4.dp))
                        .padding(2.dp),
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(MaterialTheme.padding.large),
        ) {
            Text(
                text = stringResource(MR.strings.translation_tap_to_hide),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
            )
        }
    }
}
