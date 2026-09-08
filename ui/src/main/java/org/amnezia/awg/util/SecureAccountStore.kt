/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the account number at rest - it's the actual credential
 * (Mullvad-style: possession of the code is what signs you in), so it
 * shouldn't sit in plain SharedPreferences even though allowBackup="false"
 * already blocks the easy ADB-backup extraction path. A rooted device could
 * still read app-private files directly.
 *
 * This hand-rolls AES-256-GCM against a key held in the Android Keystore
 * (the key itself never leaves hardware-backed storage / is never exported)
 * rather than depending on androidx.security:security-crypto
 * (EncryptedSharedPreferences) - Google deprecated that whole library in
 * 2025 with no stable replacement, in favour of exactly this: direct
 * Keystore + Cipher usage. Not worth taking on an abandoned dependency for
 * the one field in this app that actually needs it.
 *
 * Transparently migrates any existing plaintext "account_number" value the
 * first time it's read, then removes the plaintext copy.
 */
object SecureAccountStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "kapo_account_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private const val KEY_PLAIN = "account_number"       // legacy, migrated away from
    private const val KEY_ENCRYPTED = "account_number_enc"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // GCM IV is 12 bytes, fixed length - safe to just prefix it, both base64.
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) null else {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null // corrupted/foreign-device value (Keystore keys aren't portable) - treat as absent
    }

    fun save(prefs: SharedPreferences, account: String) {
        prefs.edit().putString(KEY_ENCRYPTED, encrypt(account)).remove(KEY_PLAIN).apply()
    }

    /** Reads the account number, migrating a legacy plaintext value in place if found. */
    fun read(prefs: SharedPreferences): String {
        val legacyPlain = prefs.getString(KEY_PLAIN, null)
        if (!legacyPlain.isNullOrEmpty()) {
            save(prefs, legacyPlain)
            return legacyPlain
        }
        val encoded = prefs.getString(KEY_ENCRYPTED, null) ?: return ""
        return decrypt(encoded) ?: ""
    }
}
