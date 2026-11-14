package com.rext.remote.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.flyfishxu.kadb.cert.KadbCert
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Kadb 1.3's certificate and private key together in one AES-GCM encrypted payload.
 * The AES key is non-exportable and remains in Android Keystore.
 */
class EncryptedAdbKeyStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("rext_adb_identity", Context.MODE_PRIVATE)

    @Synchronized
    fun configure() {
        val existing = runCatching { readIdentity() }.getOrNull()
        if (existing != null) {
            KadbCert.set(existing.first, existing.second)
            return
        }

        // Kadb 1.3 creates and keeps the PEM certificate/private-key pair in KadbCert.
        KadbCert.get(notAfter = System.currentTimeMillis() + TEN_YEARS_MS)
        val pair = KadbCert.getOrError()
        writeIdentity(pair.first, pair.second)
    }

    private fun readIdentity(): Pair<ByteArray, ByteArray>? {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        require(sealed.size > IV_LENGTH + TAG_LENGTH_BYTES) { "Encrypted ADB identity is incomplete" }

        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val ciphertext = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)

        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val certLength = input.readInt()
            require(certLength in 1..MAX_IDENTITY_FIELD)
            val certificate = ByteArray(certLength)
            input.readFully(certificate)

            val keyLength = input.readInt()
            require(keyLength in 1..MAX_IDENTITY_FIELD)
            val privateKey = ByteArray(keyLength)
            input.readFully(privateKey)
            require(input.available() == 0) { "Unexpected ADB identity payload data" }
            certificate to privateKey
        }
    }

    private fun writeIdentity(certificate: ByteArray, privateKey: ByteArray) {
        require(certificate.isNotEmpty() && privateKey.isNotEmpty())
        require(certificate.size <= MAX_IDENTITY_FIELD && privateKey.size <= MAX_IDENTITY_FIELD)
        val plaintext = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(certificate.size)
                output.write(certificate)
                output.writeInt(privateKey.size)
                output.write(privateKey)
            }
        }.toByteArray()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val sealed = cipher.iv + cipher.doFinal(plaintext)
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .commit()
        ) { "Could not save encrypted ADB identity" }
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
        private const val MAX_IDENTITY_FIELD = 16 * 1024
        private const val TEN_YEARS_MS = 10L * 365L * 24L * 60L * 60L * 1000L
    }
}

/** Configure the persisted Kadb host identity once before creating an ADB client. */
object AdbIdentity {
    @Volatile private var configured = false

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        EncryptedAdbKeyStore(context.applicationContext).configure()
        configured = true
    }
}
