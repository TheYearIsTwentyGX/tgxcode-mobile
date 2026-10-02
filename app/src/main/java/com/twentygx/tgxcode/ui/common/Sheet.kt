package com.twentygx.tgxcode.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The one bottom sheet every surface past the PR sheet uses: a title, an optional
 * subtitle, a rule, and the content.
 *
 * The phone's answer to the desktop's side panels. There is no "beside" at 390px, so
 * the queue, the changes drawer, the snippets and the rest come up from the bottom
 * over whatever they belong to, and go away with a swipe.
 *
 * [skipPartiallyExpanded] is for sheets whose content is a list the reader came to
 * scroll — half a sheet of diff is less use than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TgxSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier.padding(
                    start = Metrics.ScreenPadding,
                    end = Metrics.ScreenPadding,
                    bottom = 10.dp,
                ),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = TgxType.mono12,
                        color = tgx.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            HorizontalDivider(color = tgx.outlineSoft)
            content()
        }
    }
}
