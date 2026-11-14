package com.rext.remote.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kadb host identity store. The ADB private key is encrypted with an AES-GCM key
 * held by Android Keystore; only the encrypted payload is stored in app-private prefs.
 */
class EncryptedAdbKeyStore(context: Context) : KadbPrivateKeyStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("rext_adb_identity", Context.MODE_PRIVATE)

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val sealed = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrElse { throw IllegalStateException("Stored ADB identity is unreadable", it) }
        require(sealed.size > IV_LENGTH + TAG_LENGTH_BYTES) { "Stored ADB identity is incomplete" }
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val ciphertext = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty()) { "ADB identity cannot be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val sealed = cipher.iv + cipher.doFinal(privateKeyPem)
        val committed = preferences.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit()
        check(committed) { "Could not save encrypted ADB identity" }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(KEY_PAYLOAD).commit()) {
            "Could not remove encrypted ADB identity"
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rext.remote.adb.identity.aes"
        private const val KEY_PAYLOAD = "encrypted_private_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    }
}

/** Configure Kadb once before creating any ADB client. */
object AdbIdentity {
    @Volatile private var configured = false

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        KadbCert.configure(EncryptedAdbKeyStore(context.applicationContext))
        configured = true
    }
}
