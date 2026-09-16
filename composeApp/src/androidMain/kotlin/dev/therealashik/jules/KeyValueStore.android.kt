package dev.therealashik.jules

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual class KeyValueStore {
    private val prefs: SharedPreferences =
        AppContext.get().getSharedPreferences("jules_prefs", Context.MODE_PRIVATE)

    actual fun getString(key: String, defaultValue: String): String {
        if (key != API_KEY_KEY) {
            return prefs.getString(key, defaultValue) ?: defaultValue
        }

        val stored = prefs.getString(key, null) ?: return defaultValue
        return try {
            decrypt(stored)
        } catch (_: Exception) {
            // Migrate a legacy plaintext API key once, if one exists.
            if (stored.isNotBlank()) {
                runCatching { putString(key, stored) }
            }
            stored
        }
    }

    actual fun putString(key: String, value: String) {
        if (key != API_KEY_KEY) {
            prefs.edit().putString(key, value).apply()
            return
        }

        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }

        prefs.edit().putString(key, encrypt(value)).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted API key" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val API_KEY_KEY = "api_key"
        const val KEY_ALIAS = "jules_api_key_encryption"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

object AppContext {
    private lateinit var context: Context
    fun init(ctx: Context) { context = ctx.applicationContext }
    fun get(): Context = context
}
