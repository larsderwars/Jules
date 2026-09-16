package dev.therealashik.jules

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual class KeyValueStore {
    private val prefs: SharedPreferences =
        AppContext.get().getSharedPreferences("jules_prefs", Context.MODE_PRIVATE)

    actual fun getString(key: String, defaultValue: String): String {
        val stored = prefs.getString(key, null) ?: return defaultValue
        if (key != API_KEY_KEY || !stored.startsWith(ENCRYPTED_PREFIX)) {
            if (key == API_KEY_KEY && stored.isNotBlank()) {
                // Migrate the legacy plaintext value on first read.
                runCatching { putString(key, stored) }
            }
            return stored
        }

        return runCatching { decrypt(stored.removePrefix(ENCRYPTED_PREFIX)) }
            .getOrDefault(defaultValue)
    }

    actual fun putString(key: String, value: String) {
        if (key == API_KEY_KEY && value.isNotBlank()) {
            val encrypted = runCatching { ENCRYPTED_PREFIX + encrypt(value) }.getOrNull()
            if (encrypted != null) {
                prefs.edit().putString(key, encrypted).apply()
                return
            }
        }
        prefs.edit().putString(key, value).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        keyGenerator.init(256)
        return keyGenerator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + ciphertext.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.int
        require(ivSize in 12..16)
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        const val API_KEY_KEY = "api_key"
        const val KEY_ALIAS = "jules_api_key"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

object AppContext {
    private lateinit var context: Context
    fun init(ctx: Context) { context = ctx.applicationContext }
    fun get(): Context = context
}
