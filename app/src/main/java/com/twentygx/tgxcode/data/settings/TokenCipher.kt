package com.twentygx.tgxcode.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the bearer token with an AES-256-GCM key held in the AndroidKeyStore.
 *
 * Not `androidx.security:security-crypto`: it was deprecated in April 2025 at
 * `1.1.0-alpha07`, never reached stable, and carried real defects (main-thread I/O
 * StrictMode violations, keyset-corruption crashes on some OEMs). Adding a
 * deprecated alpha to a new project would be hard to defend, and the replacement is
 * about fifty lines with no dependency at all.
 *
 * Why bother when app-private storage is already sandboxed and the device is
 * file-based-encrypted: **this token can start processes on the user's machine.** A
 * KeyStore-bound key makes an extracted copy useless off-device, which covers the
 * cases that actually happen — a cloud backup, `run-as` on a debuggable build, a
 * forensic image.
 */
object TokenCipher {

    private const val KEY_ALIAS = "tgxcode_token"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // IV first, then ciphertext+tag, so decrypt needs no second field.
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * Returns null rather than throwing when the key has been invalidated — a
     * factory reset or a restored backup makes the stored blob permanently
     * unreadable, and the right response is to ask the user to pair again, not to
     * crash on launch.
     */
    fun decrypt(packedBase64: String): String? = runCatching {
        val packed = Base64.decode(packedBase64, Base64.NO_WRAP)
        if (packed.size <= IV_LENGTH) return@runCatching null
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val body = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Deliberately not requiring user auth: the foreground service must
                // be able to answer an ask on a locked phone, which is the point.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
