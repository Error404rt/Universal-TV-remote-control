package com.rext.remote.connection

import android.content.Context
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class AdbEndpointKind { TCP, TLS_CONNECT, TLS_PAIRING }

data class AdbEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val kind: AdbEndpointKind
) {
    val key: String get() = "$host:$port:$kind"
    val isPairingEndpoint: Boolean get() = kind == AdbEndpointKind.TLS_PAIRING
}

class AdbDiscovery(context: Context) : AutoCloseable {
    private val mdns = KadbMdnsAndroid(context.applicationContext)
    private val seen = ConcurrentHashMap<String, AdbEndpoint>()
    private var collector: Job? = null

    /**
     * Starts protocol-aware ADB mDNS discovery. The callback receives endpoints for
     * classic ADB, TLS connect, and wireless-debugging pairing separately.
     */
    fun start(scope: CoroutineScope, onEndpoint: (AdbEndpoint) -> Unit) {
        stop()
        collector = scope.launch {
            mdns.state.collect { state ->
                (state.connectDevices + state.pairDevices).forEach { service ->
                    val endpoint = service.toEndpoint() ?: return@forEach
                    if (seen.putIfAbsent(endpoint.key, endpoint) == null) {
                        onEndpoint(endpoint)
                    }
                }
            }
        }
        mdns.start()
    }

    /**
     * Best-effort fallback for TVs that expose classic ADB over TCP (usually port 5555)
     * but do not advertise an ADB mDNS record. Scans only private IPv4 interface subnets,
     * caps each subnet at 510 hosts, and limits concurrent socket probes.
     */
    suspend fun scanClassicAdb(
        port: Int = 5555,
        connectTimeoutMs: Int = 300
    ): List<AdbEndpoint> = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        val hosts = enumeratePrivateHosts().take(MAX_TOTAL_HOSTS)
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)

        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probe(host, port, connectTimeoutMs)
                }
            }
        }.awaitAll().filterNotNull()
            .distinctBy { it.key }
    }

    fun snapshot(): List<AdbEndpoint> = seen.values.sortedWith(
        compareBy<AdbEndpoint> { it.host }.thenBy { it.port }
    )

    fun stop() {
        collector?.cancel()
        collector = null
        mdns.stop()
        seen.clear()
    }

    override fun close() {
        stop()
        mdns.close()
    }

    private fun MdnsEndpoint.toEndpoint(): AdbEndpoint? {
        if (port !in 1..65535 || host.isBlank()) return null
        val kind = when (serviceType) {
            MdnsServiceType.ADB -> AdbEndpointKind.TCP
            MdnsServiceType.TLS_CONNECT -> AdbEndpointKind.TLS_CONNECT
            MdnsServiceType.TLS_PAIRING -> AdbEndpointKind.TLS_PAIRING
        }
        return AdbEndpoint(name.ifBlank { host }, host, port, kind)
    }

    private fun enumeratePrivateHosts(): List<String> {
        val output = LinkedHashSet<String>()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        for (network in interfaces) {
            if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
            for (entry in network.interfaceAddresses) {
                val address = entry.address as? Inet4Address ?: continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || !address.isSiteLocalAddress) continue

                val prefix = entry.networkPrefixLength.toInt()
                if (prefix !in 16..30) continue
                val hostCount = (1L shl (32 - prefix)) - 2L
                if (hostCount <= 0 || hostCount > MAX_HOSTS_PER_SUBNET) continue

                val ip = ByteBuffer.wrap(address.address).int.toLong() and IPV4_MASK
                val mask = (IPV4_MASK shl (32 - prefix)) and IPV4_MASK
                val firstHost = (ip and mask) + 1L
                val lastHost = (ip and mask) + hostCount
                for (value in firstHost..lastHost) {
                    if (value == ip) continue
                    output += ipv4(value)
                    if (output.size >= MAX_TOTAL_HOSTS) return output.toList()
                }
            }
        }
        return output.toList()
    }

    private fun probe(host: String, port: Int, timeoutMs: Int): AdbEndpoint? {
        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            AdbEndpoint("ADB device", host, port, AdbEndpointKind.TCP)
        }.getOrNull()
    }

    private fun ipv4(value: Long): String = listOf(
        (value shr 24) and 0xff,
        (value shr 16) and 0xff,
        (value shr 8) and 0xff,
        value and 0xff
    ).joinToString(".")

    companion object {
        private const val IPV4_MASK = 0xffff_ffffL
        private const val MAX_HOSTS_PER_SUBNET = 510L
        private const val MAX_TOTAL_HOSTS = 1024
        private const val MAX_CONCURRENT_PROBES = 24
    }
}
