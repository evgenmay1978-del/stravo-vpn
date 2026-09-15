package com.stravo.vpn.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    fun protect(value: ByteArray): ByteArray
    fun unprotect(value: ByteArray): ByteArray
}

class AndroidSecretStore : SecretStore {
    private val keyStoreName = "AndroidKeyStore"
    private val keyAlias = "stravo.profile.payload"
    private val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
    init {
        if (!keyStore.containsAlias(keyAlias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreName)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generator.generateKey()
        }
    }

    override fun protect(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value)
        return cipher.iv + encrypted
    }

    override fun unprotect(value: ByteArray): ByteArray {
        require(value.size > 12) { "Protected payload is too short" }
        val iv = value.copyOfRange(0, 12)
        val encrypted = value.copyOfRange(12, value.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey =
        (keyStore.getKey(keyAlias, null) as? SecretKey)
            ?: error("STRAVO profile key is unavailable")
}
