package com.universalremote.app.connection

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Pairing digest shared by the Android TV Remote v2 pairing exchange. */
object PairingCrypto {
    val CODE_PATTERN = Regex("[0-9a-fA-F]{6}")

    fun digest(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        require(code.matches(CODE_PATTERN)) {
            "Код должен содержать шесть символов 0–9 или A–F"
        }
        val codeTail = byteArrayOf(
            code.substring(2, 4).toInt(16).toByte(),
            code.substring(4, 6).toInt(16).toByte()
        )
        return MessageDigest.getInstance("SHA-256").digest(
            unsigned(client.modulus) +
                unsigned(client.publicExponent) +
                unsigned(server.modulus) +
                unsigned(server.publicExponent) +
                codeTail
        )
    }

    fun matchesPrefix(digest: ByteArray, code: String): Boolean {
        if (!code.matches(CODE_PATTERN) || digest.isEmpty()) return false
        return (digest[0].toInt() and 0xff) == code.substring(0, 2).toInt(16)
    }

    fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }
}
