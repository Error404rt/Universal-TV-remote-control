package com.universalremote.app.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RemoteWireTest {
    @Test
    fun varintEncodesBoundaryValues() {
        assertArrayEquals(byteArrayOf(0), RemoteWire.varint(0))
        assertArrayEquals(byteArrayOf(127), RemoteWire.varint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), RemoteWire.varint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), RemoteWire.varint(300))
    }

    @Test
    fun keyEventUsesRemoteMessageFieldTen() {
        val message = RemoteWire.parse(RemoteWire.key(23))
        val keyField = message.single { it.number == 10 }
        val event = RemoteWire.parse(keyField.bytes!!)

        assertEquals(23, event.num(1))
        assertEquals(RemoteWire.KEY_SHORT, event.num(2))
    }

    @Test
    fun keyDirectionIsEncoded() {
        val event = RemoteWire.parse(
            RemoteWire.parse(RemoteWire.key(19, RemoteWire.KEY_DOWN)).single { it.number == 10 }.bytes!!
        )
        assertEquals(RemoteWire.KEY_DOWN, event.num(2))
    }

    @Test
    fun appLinkUsesMarketLaunchUri() {
        val message = RemoteWire.parse(RemoteWire.appLink("com.google.android.youtube.tv"))
        val appField = message.single { it.number == 90 }
        val app = RemoteWire.parse(appField.bytes!!)
        assertEquals(
            "market://launch?id=com.google.android.youtube.tv",
            app.single { it.number == 1 }.bytes!!.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun appLinkRejectsInvalidPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteWire.appLink("../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteWire.appLink("single")
        }
    }

    @Test
    fun frameRejectsOversizedPayload() {
        val oversized = RemoteWire.varint((RemoteWire.MAX_FRAME_SIZE + 1).toLong())
        assertThrows(IllegalArgumentException::class.java) {
            RemoteWire.frame(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun frameReadsExactPayload() {
        val payload = RemoteWire.bytes(2, "hello".toByteArray())
        val framed = RemoteWire.varint(payload.size.toLong()) + payload
        assertArrayEquals(payload, RemoteWire.frame(ByteArrayInputStream(framed)))
    }

    @Test
    fun skipsFixed32AndFixed64Fields() {
        val bytes = byteArrayOf(
            0x0D, 1, 2, 3, 4,
            0x11, 1, 2, 3, 4, 5, 6, 7, 8,
            0x10, 7
        )
        val fields = RemoteWire.parse(bytes)
        assertEquals(listOf(2), fields.map { it.number })
        assertEquals(7L, fields.last().value)
        assertTrue(fields.last().bytes == null)
    }
}
