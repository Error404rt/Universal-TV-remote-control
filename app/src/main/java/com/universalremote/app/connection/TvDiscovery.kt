package com.universalremote.app.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Local TV discovery through Android's built-in DNS-SD (NsdManager), plus a
 * bounded IPv4 fallback for TVs that do not advertise mDNS.
 *
 * NsdManager can only resolve one service at a time on many Android versions,
 * so resolutions are serialized through a single worker instead of being fired
 * concurrently from onServiceFound.
 */
class TvDiscovery(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private val seen = ConcurrentHashMap<String, TvEndpoint>()
    private val generation = AtomicInteger(0)
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val started = mutableSetOf<String>()

    @Volatile
    private var callback: ((TvEndpoint) -> Unit)? = null

    @Volatile
    private var resolveQueue: Channel<ResolveRequest>? = null

    @Volatile
    private var resolveWorker: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope, onEndpoint: (TvEndpoint) -> Unit) {
        stop()
        callback = onEndpoint
        runCatching {
            multicastLock = wifiManager?.createMulticastLock("universal_remote_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val currentGeneration = generation.get()
        val queue = Channel<ResolveRequest>(Channel.UNLIMITED)
        resolveQueue = queue
        resolveWorker = scope.launch(Dispatchers.IO) {
            for (request in queue) {
                if (generation.get() != request.generation) continue
                val resolved = resolve(request.info) ?: continue
                if (generation.get() != request.generation) continue
                publish(resolved, request.kind)
            }
        }

        SERVICE_TYPES.forEach { (serviceType, kind) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    synchronized(this@TvDiscovery) {
                        if (generation.get() == currentGeneration) started += serviceType
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (generation.get() != currentGeneration) return
                    val advertisedType = serviceInfo.serviceType.orEmpty()
                        .removeSuffix(".")
                        .lowercase()
                    if (advertisedType != serviceType.removeSuffix(".").lowercase()) return
                    resolveQueue?.trySend(
                        ResolveRequest(serviceInfo, kind, currentGeneration)
                    )
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    // Keep rows visible until the user refreshes. mDNS packets are
                    // lossy in Wi-Fi power saving and would otherwise flicker.
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    synchronized(this@TvDiscovery) { started.remove(serviceType) }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(this@TvDiscovery) { started.remove(serviceType) }
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
     * Bounded fallback for TVs that keep mDNS behind a firewall. Probes the
     * Android TV Remote control port and, optionally, classic ADB port 5555.
     */
    suspend fun scanLocal(
        knownHosts: Set<String> = emptySet(),
        includeAdb: Boolean = true,
        connectTimeoutMs: Int = 260
    ): List<TvEndpoint> = withContext(Dispatchers.IO) {
        require(connectTimeoutMs in 100..5000)
        val hosts = enumeratePrivateHosts().take(MAX_TOTAL_HOSTS)
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    buildList {
                        if (host !in knownHosts) {
                            probe(
                                host,
                                ANDROID_TV_REMOTE_PORT,
                                connectTimeoutMs,
                                TvEndpointKind.ANDROID_TV_REMOTE,
                                "Android TV / Google TV"
                            )?.let(::add)
                        }
                        if (includeAdb) {
                            probe(
                                host,
                                ADB_PORT,
                                connectTimeoutMs,
                                TvEndpointKind.ADB,
                                "ADB device"
                            )?.let(::add)
                        }
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.key }
    }

    /** Probe one saved/entered TV even when its subnet is no longer being scanned. */
    suspend fun probeHost(
        host: String,
        includeAdb: Boolean = true,
        connectTimeoutMs: Int = 700
    ): List<TvEndpoint> = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isPrivateIpv4(host)) return@withContext emptyList()
        buildList {
            probe(
                host,
                ANDROID_TV_REMOTE_PORT,
                connectTimeoutMs,
                TvEndpointKind.ANDROID_TV_REMOTE,
                "Android TV / Google TV"
            )?.let(::add)
            if (includeAdb) {
                probe(host, ADB_PORT, connectTimeoutMs, TvEndpointKind.ADB, "ADB device")?.let(::add)
            }
        }
    }

    fun snapshot(): List<TvEndpoint> = seen.values.sortedWith(
        compareBy<TvEndpoint> { it.host }.thenBy { it.kind.ordinal }
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
        resolveQueue?.close()
        resolveQueue = null
        resolveWorker?.cancel()
        resolveWorker = null
        callback = null
        seen.clear()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    override fun close() = stop()

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                        ) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (continuation.isActive) continuation.resume(serviceInfo)
                        }
                    })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private suspend fun publish(info: NsdServiceInfo, kind: TvEndpointKind) {
        @Suppress("DEPRECATION")
        val host = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        } else {
            info.host?.hostAddress
        } ?: return
        if (!NetworkUtils.isPrivateIpv4(host) || info.port !in 1..65535) return

        val attributes = runCatching { info.attributes }.getOrNull()
        val model = attributes?.get("md")?.toString(Charsets.UTF_8).orEmpty()
        val friendlyName = attributes?.get("fn")?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
        val fallbackName = when (kind) {
            TvEndpointKind.ANDROID_TV_REMOTE -> "Android TV / Google TV"
            TvEndpointKind.ADB -> "ADB device"
            TvEndpointKind.BLUETOOTH -> "Bluetooth TV"
        }
        val serviceName = info.serviceName?.take(96).orEmpty()
        val endpoint = TvEndpoint(
            name = friendlyName ?: serviceName.takeIf { it.isNotBlank() } ?: fallbackName,
            host = host,
            port = info.port,
            kind = kind,
            model = model.takeIf { it.isNotBlank() }
        )
        if (seen.containsKey(endpoint.key)) return
        // Some firmwares advertise a service while the port is not listening.
        // Only surface endpoints that actually accept a TCP connection.
        if (!portOpen(host, info.port, MDNS_PROBE_TIMEOUT_MS)) return
        if (seen.putIfAbsent(endpoint.key, endpoint) == null) {
            callback?.invoke(endpoint)
        }
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
                if (!NetworkUtils.isPrivateIpv4(address)) continue
                val prefix = entry.networkPrefixLength.toInt()
                if (prefix !in 16..30) continue

                // A /16 would be 65k probes. Scan the local /24 around this
                // address instead; that covers every normal home/hotspot setup.
                val effectivePrefix = maxOf(prefix, 24)
                val hostCount = (1L shl (32 - effectivePrefix)) - 2L
                if (hostCount !in 1..MAX_HOSTS_PER_SUBNET) continue

                val ip = ByteBuffer.wrap(address.address).int.toLong() and IPV4_MASK
                val mask = (IPV4_MASK shl (32 - effectivePrefix)) and IPV4_MASK
                val firstHost = (ip and mask) + 1L
                val lastHost = (ip and mask) + hostCount
                for (value in firstHost..lastHost) {
                    if (value == ip) continue
                    output += NetworkUtils.ipv4(value)
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
        kind: TvEndpointKind,
        name: String
    ): TvEndpoint? = if (portOpen(host, port, timeoutMs)) {
        TvEndpoint(name, host, port, kind)
    } else {
        null
    }

    private fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
    }.isSuccess

    private data class ResolveRequest(
        val info: NsdServiceInfo,
        val kind: TvEndpointKind,
        val generation: Int
    )

    companion object {
        private const val IPV4_MASK = 0xffff_ffffL
        private const val MAX_HOSTS_PER_SUBNET = 510L
        private const val MAX_TOTAL_HOSTS = 1024
        private const val MAX_CONCURRENT_PROBES = 32
        private const val RESOLVE_TIMEOUT_MS = 3_500L
        private const val MDNS_PROBE_TIMEOUT_MS = 700
        private const val ADB_PORT = 5555
        private const val ANDROID_TV_REMOTE_PORT = 6466

        private val SERVICE_TYPES = listOf(
            "_androidtvremote2._tcp." to TvEndpointKind.ANDROID_TV_REMOTE,
            "_adb._tcp." to TvEndpointKind.ADB
        )
    }
}
