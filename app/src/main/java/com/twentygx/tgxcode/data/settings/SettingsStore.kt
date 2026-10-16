package com.twentygx.tgxcode.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.twentygx.tgxcode.data.net.BridgeConfig
import com.twentygx.tgxcode.data.net.PairingInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
