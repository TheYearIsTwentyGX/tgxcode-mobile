package com.twentygx.tgxcode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * Pairing, without the cookie handshake.
 *
 * `docs/api.md`: "A native client does not need this — it should store the token and
 * send the header." But the desktop already generates a perfectly good
 * `https://host/pair?token=…` link, and pasting that whole thing beats retyping 43
 * characters of base64url on a phone keyboard by a wide margin. So the primary input
 * is the link, and the parsed origin and token show up underneath as editable fields
 * for the manual case.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onPasteChanged: (String) -> Unit,
    onOriginChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onForget: () -> Unit,
    onStayConnectedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Null until paired: the snippets live on the bridge, so there is nothing to edit before. */
    onOpenSnippets: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Metrics.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        onOpenSnippets?.let {
            androidx.compose.material3.OutlinedButton(
                onClick = it,
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget),
            ) { Text("Snippets…") }
        }
        Text(
            "Paste the pairing link from the desktop",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "On the desktop, press the phone button in the top bar. Paste the whole " +
                "link here — the address and the token are both in it.",
            style = MaterialTheme.typography.bodySmall,
            color = tgx.textMuted,
        )
        OutlinedTextField(
            value = state.paste,
            onValueChange = onPasteChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("https://…/pair?token=…") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        state.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tgx.yellow)
        }

        Text("Bridge address", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        OutlinedTextField(
            value = state.origin,
            onValueChange = onOriginChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            singleLine = true,
            placeholder = { Text("https://tgxcode.com") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Text("Token", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        OutlinedTextField(
            value = state.token,
            onValueChange = onTokenChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onTest,
                enabled = !state.testing,
                shape = Pill,
                colors = ButtonDefaults.buttonColors(containerColor = tgx.s5),
                modifier = Modifier.weight(1f).heightIn(min = Metrics.TouchTarget),
            ) { Text(if (state.testing) "Testing…" else "Test connection") }

            Button(
                onClick = onSave,
                enabled = state.canSave,
                shape = Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tgx.blueDeep,
                    contentColor = Color.White,
                    disabledContainerColor = tgx.s4,
                    disabledContentColor = tgx.textFaint,
                ),
                modifier = Modifier.weight(1f).heightIn(min = Metrics.TouchTarget),
            ) { Text("Save") }
        }

        state.probe?.let { probe ->
            Text(
                probe.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (probe.ok) tgx.green else tgx.red,
            )
        }

        // The background toggle. The copy names the stakes rather than the battery,
        // because the battery is the cost and the stakes are the reason.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Stay connected in the background", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "The bridge denies a permission the moment it has nobody to ask. " +
                        "Leave this on and your phone counts as somebody. It holds a " +
                        "connection open, which uses some battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                )
            }
            Switch(checked = state.stayConnected, onCheckedChange = onStayConnectedChanged)
        }

        TextButton(onClick = onForget, modifier = Modifier.padding(top = 8.dp)) {
            Text("Forget this bridge", color = tgx.red)
        }
    }
}

data class ProbeResult(val text: String, val ok: Boolean)

data class SettingsUiState(
    val paste: String = "",
    val origin: String = "",
    val token: String = "",
    val note: String? = null,
    val testing: Boolean = false,
    val probe: ProbeResult? = null,
    val stayConnected: Boolean = false,
) {
    val canSave: Boolean get() = origin.isNotBlank() && token.isNotBlank()
}

/**
 * Shown when the bridge answers but refuses us.
 *
 * This screen exists because the failure is genuinely ambiguous otherwise: static
 * files need no token, so an unpaired phone would get the whole app shell, a
 * connecting dot and no data — which reads as a broken bridge rather than an
 * unsigned device. Copy carried over from mobile.js, which gets the distinction
 * exactly right.
 */
@Composable
fun NotPairedScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        EmptyState(
            headline = "This device is not paired",
            detail = "The bridge is reachable — it is refusing these requests because " +
                "this device has no token yet.\n\nOn the desktop, press the phone " +
                "button in the top bar for a pairing link, and paste it here.",
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onOpenSettings,
            shape = Pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = tgx.blueDeep,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(Metrics.ScreenPadding)
                .heightIn(min = Metrics.PrimaryButtonHeight),
        ) { Text("Pair this device") }
    }
}
