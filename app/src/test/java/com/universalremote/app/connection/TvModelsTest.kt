package com.universalremote.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvModelsTest {
    private val remote = TvEndpoint(
        name = "Living room",
        host = "192.168.1.10",
        port = 6466,
        kind = TvEndpointKind.ANDROID_TV_REMOTE
    )
    private val adb = TvEndpoint(
        name = "Living room",
        host = "192.168.1.10",
        port = 5555,
        kind = TvEndpointKind.ADB
    )
    private val saved = SavedTv(
        id = SavedTv.idFor(TvProtocol.ADB, "192.168.1.10"),
        name = "Living room TV",
        host = "192.168.1.10",
        port = 5555,
        protocol = TvProtocol.ADB,
        method = ConnectionMethod.WIFI
    )

    @Test
    fun mergesEndpointsAndSavedTvIntoOneCandidate() {
        val candidates = buildTvCandidates(listOf(remote, adb), listOf(saved))

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("Living room TV", candidate.name)
        assertNotNull(candidate.saved)
        assertEquals(listOf(TvProtocol.ANDROID_TV_REMOTE, TvProtocol.ADB), candidate.protocols)
        assertEquals(TvProtocol.ANDROID_TV_REMOTE, candidate.preferredEndpoint()?.protocol)
    }

    @Test
    fun offlineSavedTvRemainsSelectable() {
        val candidates = buildTvCandidates(emptyList(), listOf(saved))

        assertEquals(1, candidates.size)
        assertEquals("SAVED", candidates.single().statusLabel())
        assertEquals(TvProtocol.ADB, candidates.single().preferredEndpoint()?.protocol)
    }

    @Test
    fun bluetoothSavedTvIsRecognised() {
        val bluetooth = SavedTv(
            id = SavedTv.idFor(TvProtocol.BLUETOOTH_HID, SavedTv.BLUETOOTH_HOST),
            name = "Bluetooth TV",
            host = SavedTv.BLUETOOTH_HOST,
            port = 0,
            protocol = TvProtocol.BLUETOOTH_HID,
            method = ConnectionMethod.BLUETOOTH
        )
        val candidate = buildTvCandidates(emptyList(), listOf(bluetooth)).single()

        assertTrue(candidate.isBluetooth)
        assertEquals("BLUETOOTH", candidate.statusLabel())
        assertEquals(TvProtocol.BLUETOOTH_HID, candidate.preferredEndpoint()?.protocol)
    }

    @Test
    fun candidateWithoutSavedDataUsesDiscoveredProtocol() {
        val candidate = buildTvCandidates(listOf(remote), emptyList()).single()

        assertNull(candidate.saved)
        assertEquals("TV REMOTE", candidate.statusLabel())
        assertEquals("192.168.1.10", candidate.subtitle())
    }

    @Test
    fun savedTvsSortBeforeDiscoveredOnes() {
        val discovered = TvEndpoint("Aardvark", "192.168.1.20", 6466, TvEndpointKind.ANDROID_TV_REMOTE)
        val candidates = buildTvCandidates(listOf(discovered), listOf(saved))

        assertEquals("Living room TV", candidates.first().name)
    }

    @Test
    fun xiaomiModelCodeBecomesFriendlyName() {
        assertEquals("Xiaomi Mi TV 4A 32", TvModelCatalog.displayName("Xiaomi", "MiTV-MSSP0"))
        assertEquals("Mi TV 4A 32", TvModelCatalog.displayName("", "MiTV-MSSP0"))
        assertEquals("Android TV", TvModelCatalog.displayName(null, null))
    }
}
