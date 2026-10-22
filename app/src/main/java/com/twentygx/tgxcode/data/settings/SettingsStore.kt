package com.twentygx.tgxcode.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.twentygx.tgxcode.data.install.Claim
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.PairingInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("tgxcode")

/**
 * Where the bridge is, and whether to hold a connection in the background.
 *
 * Deliberately one profile rather than several for now. Two would be justifiable —
 * an `adb reverse` loopback profile for development and the Tailscale origin for
 * real use — but a switcher is a screen, and the origin is one paste away from being
 * changed. Revisit if switching turns out to be frequent.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ORIGIN = stringPreferencesKey("origin")
        /** Ciphertext, never the raw token. See [TokenCipher]. */
        val TOKEN = stringPreferencesKey("token_enc")
        val BACKGROUND = booleanPreferencesKey("stay_connected")
        /** A Cloudflare Access client ID. An identifier, not a secret, so stored plain. */
        val ACCESS_ID = stringPreferencesKey("access_id")
        /** Ciphertext, through the same [TokenCipher] as the bearer token. */
        val ACCESS_SECRET = stringPreferencesKey("access_secret_enc")
        val INSTALL_APPS = booleanPreferencesKey("install_apps")
        /** JSON list of [Claim] — the phone installs in flight. */
        val INSTALL_CLAIMS = stringPreferencesKey("phone_install_claims")
        /** A random id the bridge keys this phone's install claims on. */
        val INSTALL_VIEWER = stringPreferencesKey("phone_install_viewer")

        // What the Settings card's "Background installs" rows read, kept here so they
        // survive the process: the case worth diagnosing is the one where it was killed.
        val BG_CONNECTED_AT = longPreferencesKey("bg_connected_at")
        val BG_EVENT_AT = longPreferencesKey("bg_event_at")
        val BG_CHECK_AT = longPreferencesKey("bg_check_at")
        val BG_CHECK_RESULT = stringPreferencesKey("bg_check_result")
        val BG_BATTERY_ASKED = booleanPreferencesKey("bg_battery_asked")
    }

    val config: Flow<BridgeConfig?> = context.dataStore.data.map { prefs ->
        val origin = prefs[Keys.ORIGIN] ?: return@map null
        val token = prefs[Keys.TOKEN]?.let(TokenCipher::decrypt) ?: return@map null
        // An unreadable secret (a restored backup, a reset key) means Access is off
        // rather than the whole config vanishing: the bridge token may still be fine,
        // and the Access-refused screen says exactly what to re-enter.
        val accessSecret = prefs[Keys.ACCESS_SECRET]?.let(TokenCipher::decrypt).orEmpty()
        PairingInput.build(origin, token, prefs[Keys.ACCESS_ID].orEmpty(), accessSecret)
    }

    val origin: Flow<String?> = context.dataStore.data.map { it[Keys.ORIGIN] }

    /**
     * Off by default. Holding a socket open is a battery cost, and the user should
     * opt into it knowing what it buys them.
     */
    val stayConnected: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BACKGROUND] ?: false }

    /**
     * On by default: an install the desktop sends is something somebody asked for, and
     * every one that is not silent still waits for a tap on the phone.
     */
    val installApps: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.INSTALL_APPS] ?: true }

    suspend fun setInstallApps(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INSTALL_APPS] = enabled }
    }

    suspend fun installClaims(): List<Claim> =
        Claim.decode(context.dataStore.data.first()[Keys.INSTALL_CLAIMS])

    /** Read-modify-write inside one edit, so two installs finishing together both land. */
    suspend fun updateInstallClaims(change: (List<Claim>) -> List<Claim>) {
        context.dataStore.edit { prefs ->
            val next = change(Claim.decode(prefs[Keys.INSTALL_CLAIMS]))
            if (next.isEmpty()) prefs.remove(Keys.INSTALL_CLAIMS)
            else prefs[Keys.INSTALL_CLAIMS] = Claim.encode(next)
        }
    }

    /** Made once and kept, so a claim made before a restart is still this phone's after it. */
    suspend fun installViewerId(): String {
        context.dataStore.data.first()[Keys.INSTALL_VIEWER]?.let { return it }
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.INSTALL_VIEWER] ?: "phone-${UUID.randomUUID()}".also { prefs[Keys.INSTALL_VIEWER] = it }
        }
        return id
    }

    suspend fun save(config: BridgeConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ORIGIN] = config.origin
            prefs[Keys.TOKEN] = TokenCipher.encrypt(config.token)
            // Saved with the origin, never on their own, so one host's Access token
            // cannot end up attached to another host.
            if (config.accessId.isBlank()) prefs.remove(Keys.ACCESS_ID)
            else prefs[Keys.ACCESS_ID] = config.accessId
            if (config.accessSecret.isBlank()) prefs.remove(Keys.ACCESS_SECRET)
            else prefs[Keys.ACCESS_SECRET] = TokenCipher.encrypt(config.accessSecret)
        }
    }

    /** Epoch millis of the last time the bridge was reached, and of the last event from it. */
    val lastConnectedAt: Flow<Long?> = context.dataStore.data.map { it[Keys.BG_CONNECTED_AT] }
    val lastEventAt: Flow<Long?> = context.dataStore.data.map { it[Keys.BG_EVENT_AT] }

    suspend fun setLastConnectedAt(at: Long) {
        context.dataStore.edit { it[Keys.BG_CONNECTED_AT] = at }
    }

    suspend fun setLastEventAt(at: Long) {
        context.dataStore.edit { it[Keys.BG_EVENT_AT] = at }
    }

    /** When the fallback check last ran, and what it found. */
    val lastCheck: Flow<Pair<Long, String?>?> = context.dataStore.data.map { prefs ->
        prefs[Keys.BG_CHECK_AT]?.let { it to prefs[Keys.BG_CHECK_RESULT] }
    }

    suspend fun setLastCheck(at: Long, result: String) {
        context.dataStore.edit {
            it[Keys.BG_CHECK_AT] = at
            it[Keys.BG_CHECK_RESULT] = result
        }
    }

    /** Whether the one-time battery prompt has been shown. */
    suspend fun batteryAsked(): Boolean = context.dataStore.data.first()[Keys.BG_BATTERY_ASKED] ?: false

    suspend fun setBatteryAsked() {
        context.dataStore.edit { it[Keys.BG_BATTERY_ASKED] = true }
    }

    suspend fun setStayConnected(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND] = enabled }
    }

    suspend fun forget() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ORIGIN)
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.ACCESS_ID)
            prefs.remove(Keys.ACCESS_SECRET)
        }
    }
}
