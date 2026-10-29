package com.twentygx.tgxcode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    onAccessIdChanged: (String) -> Unit,
    onAccessSecretChanged: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onForget: () -> Unit,
    onStayConnectedChanged: (Boolean) -> Unit,
    onInstallAppsChanged: (Boolean) -> Unit,
    onThinkingExpandedChanged: (Boolean) -> Unit,
    onGrantInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null until paired: the snippets live on the bridge, so there is nothing to edit before. */
    onOpenSnippets: (() -> Unit)? = null,
    /** Null until paired, for the same reason: the queue is the bridge's. */
    onOpenInstalls: (() -> Unit)? = null,
    /** "Background installs". Null leaves the plain grant row in its place. */
    background: com.twentygx.tgxcode.data.background.BackgroundStatus? = null,
    now: Long = 0,
    onBackgroundFix: (com.twentygx.tgxcode.data.background.BackgroundRules.Fix) -> Unit = {},
    onAppDetails: () -> Unit = {},
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

        // Its own section, below the pairing fields and apart from them, because it is
        // a different credential for a different gate: the pairing link proves who we
        // are to the bridge, and these prove it to Cloudflare in front of it. The link
        // never carries them, so pasting one must not look like it should have.
        Text(
            "Cloudflare Access (optional)",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Only if this bridge sits behind Cloudflare Access. The pairing link does " +
                "not include these — copy them from Zero Trust → Access controls → " +
                "Service credentials → Service Tokens. Leave both empty to turn this off.",
            style = MaterialTheme.typography.bodySmall,
            color = tgx.textMuted,
        )

        Text("Access client ID", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        OutlinedTextField(
            value = state.accessId,
            onValueChange = onAccessIdChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            singleLine = true,
            placeholder = { Text("….access") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Text("Access client secret", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        var showSecret by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.accessSecret,
            onValueChange = onAccessSecretChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            singleLine = true,
            visualTransformation = if (showSecret) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                TextButton(onClick = { showSecret = !showSecret }) {
                    Text(if (showSecret) "Hide" else "Show")
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        if (state.accessHalfSet) {
            Text(
                "Both the client ID and the secret are needed. With only one, neither is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = tgx.yellow,
            )
        }

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

        // Phone installs. The grant row shows whether or not the toggle is on: it is
        // the half that cannot be fixed from here, so it should be visible before the
        // first install trips over it.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Install apps sent from the desktop", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "An agent that builds an Android app can send it here instead of using " +
                        "adb. Updates to apps TGXCode installed go on silently; anything " +
                        "else asks for one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                )
            }
            Switch(checked = state.installApps, onCheckedChange = onInstallAppsChanged)
        }
        // The card carries the grant as one of its rows, with the same button.
        if (background != null) {
            BackgroundCard(status = background, now = now, onFix = onBackgroundFix, onAppDetails = onAppDetails)
        } else Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Install unknown apps", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                Text(
                    if (state.canInstallApps) "Allowed" else "Not allowed — installs will wait for it",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.canInstallApps) tgx.green else tgx.yellow,
                )
            }
            if (!state.canInstallApps) {
                TextButton(onClick = onGrantInstallPermission) { Text("Allow") }
            }
        }
        onOpenInstalls?.let {
            androidx.compose.material3.OutlinedButton(
                onClick = it,
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget),
            ) { Text("Phone installs…") }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Expand thinking", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Show the model's thinking open in the transcript. Tap a block to " +
                        "fold or open just that one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tgx.textMuted,
                )
            }
            Switch(checked = state.thinkingExpanded, onCheckedChange = onThinkingExpandedChanged)
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
    val accessId: String = "",
    val accessSecret: String = "",
    val note: String? = null,
    val testing: Boolean = false,
    val probe: ProbeResult? = null,
    val stayConnected: Boolean = false,
    val installApps: Boolean = true,
    val canInstallApps: Boolean = false,
    val thinkingExpanded: Boolean = com.twentygx.tgxcode.data.settings.DEFAULT_THINKING_EXPANDED,
) {
    /** One of the two Access fields without the other — never saved, never sent. */
    val accessHalfSet: Boolean get() = accessId.isBlank() != accessSecret.isBlank()

    val canSave: Boolean get() = origin.isNotBlank() && token.isNotBlank() && !accessHalfSet

    /** Never the token or the secret, should this ever end up in a log or a crash report. */
    override fun toString(): String =
        "SettingsUiState(origin=$origin, accessId=$accessId, testing=$testing, probe=$probe)"
}

/**
 * Shown when Cloudflare Access, in front of the bridge, refuses us.
 *
 * Deliberately not [NotPairedScreen]. Strict Access answers 401 just as the bridge
 * does for a missing token, and "pair this device" would send someone to fetch a new
 * pairing link that changes nothing. Nor is it "offline": the network is fine and
 * the tunnel is up — the edge is refusing the service token.
 */
@Composable
fun AccessDeniedScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        EmptyState(
            headline = "Cloudflare Access refused this request",
            detail = "The bridge sits behind Cloudflare Access, and it did not accept " +
                "this device's service token — so nothing reaches the bridge, and " +
                "permission asks are auto-denied while this lasts.\n\nCheck the Access " +
                "client ID and secret in Settings, and that the token is included in " +
                "the application's Service Auth policy.",
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
        ) { Text("Check the service token") }
    }
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
