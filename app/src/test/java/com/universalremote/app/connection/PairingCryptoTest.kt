package com.universalremote.app.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class PairingCryptoTest {
    private fun rsa(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    @Test
    fun digestIsStableForSameInputs() {
        val client = rsa().public as RSAPublicKey
        val server = rsa().public as RSAPublicKey
        val first = PairingCrypto.digest(client, server, "A1B2C3")
        val second = PairingCrypto.digest(client, server, "A1B2C3")

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun codeChangesDigest() {
        val client = rsa().public as RSAPublicKey
        val server = rsa().public as RSAPublicKey
        assertFalse(
            PairingCrypto.digest(client, server, "000000")
                .contentEquals(PairingCrypto.digest(client, server, "000001"))
        )
    }

    @Test
    fun prefixMatchesFirstDigestByte() {
        val client = rsa().public as RSAPublicKey
        val server = rsa().public as RSAPublicKey
        val digest = PairingCrypto.digest(client, server, "000000")
        val prefix = "%02X".format(digest[0])

        assertTrue(PairingCrypto.matchesPrefix(digest, prefix + "ABCD"))
        assertFalse(PairingCrypto.matchesPrefix(digest, "%02X".format((digest[0].toInt() + 1) and 0xff) + "ABCD"))
    }

    @Test
    fun digestRejectsWrongCodeLength() {
        val client = rsa().public as RSAPublicKey
        val server = rsa().public as RSAPublicKey
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.digest(client, server, "12345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.digest(client, server, "XYZXYZ")
        }
    }

    @Test
    fun unsignedStripsSignByte() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x02),
            PairingCrypto.unsigned(java.math.BigInteger(1, byteArrayOf(0x01, 0x02)))
        )
    }
}
