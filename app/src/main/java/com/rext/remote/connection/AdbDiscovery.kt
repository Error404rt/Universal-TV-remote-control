package com.rext.remote.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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

enum class AdbEndpointKind { TCP, TLS_CONNECT, TLS_PAIRING, MI_LINK, ANDROID_TV_REMOTE_V2 }

data class AdbEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val kind: AdbEndpointKind,
    val model: String? = null
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
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
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
        runCatching {
            multicastLock = wifiManager?.createMulticastLock("universal_remote_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
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
                                @Suppress("DEPRECATION")
                                val host = if (Build.VERSION.SDK_INT >= 34) {
                                    info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                                } else {
                                    info.host?.hostAddress
                                } ?: return
                                if (!isPrivateIpv4(host) || info.port !in 1..65535) return
                                val attributes = runCatching { info.attributes }.getOrNull()
                                val model = attributes?.get("md")?.toString(Charsets.UTF_8).orEmpty()
                                val friendlyName = attributes?.get("fn")?.toString(Charsets.UTF_8)
                                    ?.takeIf { it.isNotBlank() }
                                if (kind == AdbEndpointKind.MI_LINK &&
                                    !isXiaomiModel(model, info.serviceName.orEmpty())
                                ) return
                                val endpoint = AdbEndpoint(
                                    name = friendlyName ?: info.serviceName?.take(96).orEmpty().ifBlank { "TV device" },
                                    host = host,
                                    port = if (kind == AdbEndpointKind.MI_LINK) MILINK_PORT else info.port,
                                    kind = kind,
                                    model = model.takeIf { it.isNotBlank() }
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
                semaphore.withPermit { probe(host, port, connectTimeoutMs, AdbEndpointKind.TCP, "ADB device") }
            }
        }.awaitAll().filterNotNull().distinctBy { it.key }
    }

    /** Bounded fallback scan for Android TV Remote v2 control service on TCP 6466. */
    suspend fun scanAndroidTvRemote(connectTimeoutMs: Int = 280): List<AdbEndpoint> = withContext(Dispatchers.IO) {
        require(connectTimeoutMs in 100..5000)
        val hosts = enumeratePrivateHosts().take(MAX_TOTAL_HOSTS)
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probe(host, ANDROID_TV_REMOTE_PORT, connectTimeoutMs,
                        AdbEndpointKind.ANDROID_TV_REMOTE_V2, "Android TV / Google TV")
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.key }
    }

    /** Bounded fallback scan for Xiaomi MiLink local TCP service on port 6091. */
    suspend fun scanMiLink(connectTimeoutMs: Int = 280): List<AdbEndpoint> = withContext(Dispatchers.IO) {
        require(connectTimeoutMs in 100..5000)
        val hosts = enumeratePrivateHosts().take(MAX_TOTAL_HOSTS)
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probe(host, MILINK_PORT, connectTimeoutMs, AdbEndpointKind.MI_LINK, "Xiaomi TV (MiLink)")
                }
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
        runCatching { multicastLock?.release() }
        multicastLock = null
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

    private fun probe(
        host: String,
        port: Int,
        timeoutMs: Int,
        kind: AdbEndpointKind,
        name: String
    ): AdbEndpoint? = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        AdbEndpoint(name, host, port, kind)
    }.getOrNull()

    private fun isXiaomiModel(model: String, serviceName: String): Boolean =
        listOf(model, serviceName).any { value ->
            val normalized = value.trim().lowercase()
            normalized.startsWith("mitv") || normalized.startsWith("mibox")
        }

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
        private const val MILINK_PORT = 6091
        private const val ANDROID_TV_REMOTE_PORT = 6466

        private val SERVICE_TYPES = listOf(
            "_adb._tcp." to AdbEndpointKind.TCP,
            "_adb-tls-connect._tcp." to AdbEndpointKind.TLS_CONNECT,
            "_adb-tls-pairing._tcp." to AdbEndpointKind.TLS_PAIRING,
            "_googlecast._tcp." to AdbEndpointKind.MI_LINK,
            "_androidtvremote2._tcp." to AdbEndpointKind.ANDROID_TV_REMOTE_V2
        )
    }
}
