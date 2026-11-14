package com.rext.remote.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * ADB endpoint discovery through Android's built-in DNS-SD (NsdManager).
 *
 * The app listens for the three ADB service types used by classic ADB and Android
 * Wireless debugging. This avoids a separate mDNS runtime dependency.
 */
class AdbDiscovery(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val seen = ConcurrentHashMap<String, AdbEndpoint>()
    private val generation = AtomicInteger(0)
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val started = mutableSetOf<String>()

    @Volatile
    private var callback: ((AdbEndpoint) -> Unit)? = null

    @Synchronized
    fun start(scope: CoroutineScope, onEndpoint: (AdbEndpoint) -> Unit) {
        stop()
        callback = onEndpoint
        val currentGeneration = generation.get()
        SERVICE_TYPES.forEach { (serviceType, kind) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    synchronized(this@AdbDiscovery) {
                        if (generation.get() == currentGeneration) started += serviceType
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (generation.get() != currentGeneration) return
                    val advertisedType = serviceInfo.serviceType.orEmpty()
                    if (!advertisedType.startsWith(serviceType.removeSuffix("."), ignoreCase = true)) return
                    try {
                        @Suppress("DEPRECATION")
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (generation.get() != currentGeneration) return
                                val host = info.host?.hostAddress ?: return
                                if (!isPrivateIpv4(host) || info.port !in 1..65535) return
                                val endpoint = AdbEndpoint(
                                    name = info.serviceName?.take(96).orEmpty().ifBlank { "ADB device" },
                                    host = host,
                                    port = info.port,
                                    kind = kind
                                )
                                if (seen.putIfAbsent(endpoint.key, endpoint) == null) {
                                    callback?.invoke(endpoint)
                                }
                            }
                        })
                    } catch (_: Exception) {
                        // A service can disappear between discovery and resolution.
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    // Keep discovered rows visible until the user refreshes. This avoids
                    // flicker caused by Wi-Fi power saving and multicast packet loss.
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    synchronized(this@AdbDiscovery) { started.remove(serviceType) }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(this@AdbDiscovery) { started.remove(serviceType) }
                    runCatching { manager.stopServiceDiscovery(this) }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listeners[serviceType] = listener
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                listeners.remove(serviceType)
            }
        }
    }

    /**
     * Bounded fallback for TVs exposing classic ADB over TCP port 5555 without mDNS.
     * Only private IPv4 subnets are scanned, capped at 510 hosts per subnet and
     * 1,024 total candidates, with no more than 24 simultaneous socket probes.
     */
    suspend fun scanClassicAdb(
        port: Int = 5555,
        connectTimeoutMs: Int = 300
    ): List<AdbEndpoint> = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        require(connectTimeoutMs in 100..5000)
        val hosts = enumeratePrivateHosts().take(MAX_TOTAL_HOSTS)
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit { probe(host, port, connectTimeoutMs) }
            }
        }.awaitAll().filterNotNull().distinctBy { it.key }
    }

    fun snapshot(): List<AdbEndpoint> = seen.values.sortedWith(
        compareBy<AdbEndpoint> { it.host }.thenBy { it.port }.thenBy { it.kind.ordinal }
    )

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        listeners.toMap().forEach { (serviceType, listener) ->
            if (serviceType in started) {
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
        listeners.clear()
        started.clear()
        callback = null
        seen.clear()
    }

    override fun close() = stop()

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
                if (hostCount !in 1..MAX_HOSTS_PER_SUBNET) continue

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

    private fun probe(host: String, port: Int, timeoutMs: Int): AdbEndpoint? = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        AdbEndpoint("ADB device", host, port, AdbEndpointKind.TCP)
    }.getOrNull()

    private fun isPrivateIpv4(value: String): Boolean = runCatching {
        val parts = value.split('.').map(String::toInt)
        parts.size == 4 && parts.all { it in 0..255 } &&
            (parts[0] == 10 ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168))
    }.getOrDefault(false)

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

        private val SERVICE_TYPES = listOf(
            "_adb._tcp." to AdbEndpointKind.TCP,
            "_adb-tls-connect._tcp." to AdbEndpointKind.TLS_CONNECT,
            "_adb-tls-pairing._tcp." to AdbEndpointKind.TLS_PAIRING
        )
    }
}
