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
 * лежит только шифротекст. Массовое чтение разрешено только для шифрованного backup;
 * открытые значения нельзя сохранять в файлы, UI-модели, журналы или clipboard.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val key: SecretKey? by lazy { loadOrCreateKey() }

    /** false — на устройстве недоступен Keystore: секреты сохранять нельзя, и мы это честно скажем. */
    val isAvailable: Boolean get() = key != null

    fun put(id: String, value: String): Boolean = withExclusiveAccess {
        val secretKey = key ?: return@withExclusiveAccess false
        try {
            val payload = encrypt(value, secretKey)
            prefs.edit().putString(storageKey(id), payload).commit()
        } catch (error: Exception) {
            false
        }
    }

    fun get(id: String): String? = withExclusiveAccess {
        val secretKey = key ?: return@withExclusiveAccess null
        val stored = prefs.getString(storageKey(id), null) ?: return@withExclusiveAccess null
        val separator = stored.indexOf(SEPARATOR)
        if (separator <= 0) return@withExclusiveAccess null
        try {
            val iv = decode(stored.substring(0, separator))
            val body = decode(stored.substring(separator + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            val plaintext = cipher.doFinal(body)
            try { String(plaintext, Charsets.UTF_8) } finally { plaintext.fill(0) }
        } catch (error: Exception) {
            null
        }
    }

    fun contains(id: String): Boolean = withExclusiveAccess { prefs.contains(storageKey(id)) }

    fun remove(id: String) = withExclusiveAccess {
        prefs.edit().remove(storageKey(id)).apply()
    }

    fun clear() = withExclusiveAccess {
        prefs.edit().clear().apply()
    }

    /** All or nothing: one unreadable entry aborts export, never silently drops a profile. */
    fun exportEntries(): Map<String, String>? = withExclusiveAccess {
        val stored = prefs.all
        if (stored.size > MAX_BULK_ENTRIES) return@withExclusiveAccess null
        val result = linkedMapOf<String, String>()
        var characters = 0L
        for ((storedKey, value) in stored) {
            if (!storedKey.startsWith(PREFIX) || value !is String) return@withExclusiveAccess null
            if (value.length > MAX_BULK_CHARS * 2) return@withExclusiveAccess null
            val id = storedKey.removePrefix(PREFIX)
            if (id.isBlank()) return@withExclusiveAccess null
            val plaintext = get(id) ?: return@withExclusiveAccess null
            characters += id.length.toLong() + plaintext.length
            if (characters > MAX_BULK_CHARS) return@withExclusiveAccess null
            result[id] = plaintext
        }
        result
    }

    /** Encrypt EVERY new entry before constructing an editor or clearing existing data. */
    fun prepareReplace(entries: Map<String, String>): PreparedReplacement? = withExclusiveAccess {
        try {
            if (entries.size > MAX_BULK_ENTRIES ||
                entries.entries.sumOf { it.key.length.toLong() + it.value.length } > MAX_BULK_CHARS
            ) return@withExclusiveAccess null
            val before = linkedMapOf<String, String>()
            for ((id, value) in prefs.all) {
                if (value !is String) return@withExclusiveAccess null
                before[id] = value
            }
            val after = linkedMapOf<String, String>()
            for ((id, value) in entries) {
                if (id.isBlank()) return@withExclusiveAccess null
                after[storageKey(id)] = encrypt(value, key ?: return@withExclusiveAccess null)
            }
            PreparedReplacement(before, after)
        } catch (_: Exception) {
            null
        }
    }

    /** Contains ciphertext only; rollback does not require decrypting or re-encrypting data. */
    inner class PreparedReplacement internal constructor(
        private val before: Map<String, String>,
        private val after: Map<String, String>,
    ) {
        private var attempted = false

        fun commit(): Boolean = withExclusiveAccess {
            if (attempted || prefs.all != before) return@withExclusiveAccess false
            attempted = true // commit(false) can still change SharedPreferences in memory.
            writeCiphertext(after)
        }

        fun rollback(): Boolean = withExclusiveAccess {
            if (!attempted) return@withExclusiveAccess true
            // Do not overwrite a later unrelated edit if used outside the backup transaction.
            if (prefs.all != after && prefs.all != before) return@withExclusiveAccess false
            writeCiphertext(before).also { if (it) attempted = false }
        }
    }

    /** Reentrant, process-wide lock also covers other SecretStore instances. No suspension inside. */
    internal fun <T> withExclusiveAccess(block: () -> T): T = synchronized(LOCK, block)

    private fun writeCiphertext(values: Map<String, String>): Boolean = try {
        val editor = prefs.edit().clear()
        values.forEach { (id, value) -> editor.putString(id, value) }
        editor.commit()
    } catch (_: Exception) {
        false
    }

    private fun encrypt(value: String, secretKey: SecretKey): String {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            return encode(cipher.iv) + SEPARATOR + encode(cipher.doFinal(plaintext))
        } finally {
            plaintext.fill(0)
        }
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
        val LOCK = Any()
        const val MAX_BULK_ENTRIES = 10_000
        const val MAX_BULK_CHARS = 8 * 1024 * 1024
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

