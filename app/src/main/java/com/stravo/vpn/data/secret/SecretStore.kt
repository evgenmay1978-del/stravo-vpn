package com.stravo.vpn.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Защищённое хранилище секретов подписки: полные конфиги узлов (UUID, пароли, ключи Reality).
 *
 * Ключ AES-256 живёт в Android Keystore и никогда не покидает его; в SharedPreferences
 * лежит только шифротекст. Наружу значения отдаются поштучно и только по запросу ядра
 * туннеля — в UI-модели, в логи и в бэкапы они не попадают.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val key: SecretKey? by lazy { loadOrCreateKey() }

    /** false — на устройстве недоступен Keystore: секреты сохранять нельзя, и мы это честно скажем. */
    val isAvailable: Boolean get() = key != null

    fun put(id: String, value: String): Boolean {
        val secretKey = key ?: return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = encode(cipher.iv) + SEPARATOR + encode(encrypted)
            prefs.edit().putString(storageKey(id), payload).commit()
        } catch (error: Exception) {
            false
        }
    }

    fun get(id: String): String? {
        val secretKey = key ?: return null
        val stored = prefs.getString(storageKey(id), null) ?: return null
        val separator = stored.indexOf(SEPARATOR)
        if (separator <= 0) return null
        return try {
            val iv = decode(stored.substring(0, separator))
            val body = decode(stored.substring(separator + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (error: Exception) {
            null
        }
    }

    fun contains(id: String): Boolean = prefs.contains(storageKey(id))

    fun remove(id: String) {
        prefs.edit().remove(storageKey(id)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun storageKey(id: String): String = PREFIX + id

    private fun loadOrCreateKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: generateKey()
    } catch (error: Exception) {
        null
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "stravo.subscription.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val SEPARATOR = ":"
        const val PREFIX = "node."
        const val PREFS = "stravo.secrets"
    }
}

