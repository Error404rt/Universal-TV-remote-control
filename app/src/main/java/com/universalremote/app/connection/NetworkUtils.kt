package com.universalremote.app.connection

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

data class LocalIpv4(
    val interfaceName: String,
    val address: String,
    val prefixLength: Int
)

object NetworkUtils {
    /**
     * Accepts only literal IPv4 addresses that can belong to a local network.
     * Includes the RFC1918 ranges plus the carrier-grade NAT range commonly
     * used by phone hotspots and some routers.
     */
    fun isPrivateIpv4(value: String): Boolean = parseIpv4(value)?.let(::isPrivateIpv4) == true

    fun parseIpv4(value: String): Long? = runCatching {
        val parts = value.trim().split('.')
        if (parts.size != 4) return@runCatching null
        if (parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') }) {
            return@runCatching null
        }
        val octets = parts.map(String::toInt)
        if (octets.any { it !in 0..255 }) return@runCatching null
        (octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()
    }.getOrNull()

    private fun isPrivateIpv4(value: Long): Boolean {
        val first = (value shr 24) and 0xff
        val second = (value shr 16) and 0xff
        return first == 10L ||
            (first == 172L && second in 16..31) ||
            (first == 192L && second == 168L) ||
            (first == 100L && second in 64..127)
    }

    fun isPrivateIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) {
            return false
        }
        return isPrivateIpv4(address.hostAddress.orEmpty())
    }

    fun ipv4(value: Long): String = listOf(
        (value shr 24) and 0xff,
        (value shr 16) and 0xff,
        (value shr 8) and 0xff,
        value and 0xff
    ).joinToString(".")

    /** Every usable private IPv4 address on this phone, including a hotspot interface. */
    fun localInterfaces(): List<LocalIpv4> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { network ->
                val usable = runCatching {
                    network.isUp && !network.isLoopback
                }.getOrDefault(false)
                if (!usable) return@flatMap emptyList()
                network.interfaceAddresses.mapNotNull { entry ->
                    val address = entry.address as? Inet4Address ?: return@mapNotNull null
                    if (!isPrivateIpv4(address)) return@mapNotNull null
                    LocalIpv4(
                        interfaceName = network.name.orEmpty(),
                        address = address.hostAddress.orEmpty(),
                        prefixLength = entry.networkPrefixLength.toInt()
                    )
                }
            }
            .distinctBy { it.address }
    }.getOrDefault(emptyList())

    fun localIpv4Addresses(): List<String> = localInterfaces().map { it.address }

    fun isHotspotInterfaceName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("ap") ||
            lower.contains("softap") ||
            lower.startsWith("swlan") ||
            lower == "wlan1"
    }

    fun hasHotspotInterface(): Boolean =
        localInterfaces().any { isHotspotInterfaceName(it.interfaceName) }
}
