package com.universalremote.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun acceptsPrivateIpv4Ranges() {
        assertTrue(NetworkUtils.isPrivateIpv4("10.0.0.1"))
        assertTrue(NetworkUtils.isPrivateIpv4("172.16.0.1"))
        assertTrue(NetworkUtils.isPrivateIpv4("172.31.255.254"))
        assertTrue(NetworkUtils.isPrivateIpv4("192.168.43.1"))
        assertTrue(NetworkUtils.isPrivateIpv4("100.64.0.1"))
    }

    @Test
    fun rejectsPublicOrMalformedAddresses() {
        assertFalse(NetworkUtils.isPrivateIpv4("8.8.8.8"))
        assertFalse(NetworkUtils.isPrivateIpv4("172.32.0.1"))
        assertFalse(NetworkUtils.isPrivateIpv4("127.0.0.1"))
        assertFalse(NetworkUtils.isPrivateIpv4("192.168.01.1"))
        assertFalse(NetworkUtils.isPrivateIpv4("999.1.1.1"))
        assertFalse(NetworkUtils.isPrivateIpv4("192.168.1"))
        assertFalse(NetworkUtils.isPrivateIpv4(""))
        assertNull(NetworkUtils.parseIpv4("nope"))
    }

    @Test
    fun convertsBetweenIpv4TextAndLong() {
        val value = NetworkUtils.parseIpv4("192.168.1.10")!!
        assertEquals("192.168.1.10", NetworkUtils.ipv4(value))
    }

    @Test
    fun detectsHotspotInterfaceNames() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("ap0"))
        assertTrue(NetworkUtils.isHotspotInterfaceName("swlan0"))
        assertTrue(NetworkUtils.isHotspotInterfaceName("wlan1"))
        assertFalse(NetworkUtils.isHotspotInterfaceName("wlan0"))
        assertFalse(NetworkUtils.isHotspotInterfaceName("eth0"))
    }
}
