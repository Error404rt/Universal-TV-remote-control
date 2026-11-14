package com.universalremote.app.connection

import java.util.Locale

/** The three connection choices offered by the Connect action. */
enum class ConnectionMethod(val title: String, val hint: String) {
    BLUETOOTH(
        "Bluetooth",
        "Телефон работает как HID-пульт и мышь. Сопряжение начинается на телевизоре."
    ),
    HOTSPOT(
        "Phone hotspot",
        "Телевизор подключается к точке доступа телефона и управляется по локальной сети."
    ),
    WIFI(
        "Shared Wi-Fi",
        "Телефон и телевизор находятся в одной Wi-Fi сети."
    )
}

/** Protocols this build can actually drive. */
enum class TvProtocol(
    val title: String,
    val shortTitle: String,
    val defaultPort: Int
) {
    ANDROID_TV_REMOTE("Android TV Remote", "TV REMOTE", 6466),
    ADB("ADB", "ADB", 5555),
    BLUETOOTH_HID("Bluetooth HID", "BLUETOOTH", 0);

    val endpointKind: TvEndpointKind
        get() = when (this) {
            ANDROID_TV_REMOTE -> TvEndpointKind.ANDROID_TV_REMOTE
            ADB -> TvEndpointKind.ADB
            BLUETOOTH_HID -> TvEndpointKind.BLUETOOTH
        }

    companion object {
        fun fromName(value: String?): TvProtocol? =
            entries.firstOrNull { it.name == value }
    }
}

enum class TvEndpointKind {
    ANDROID_TV_REMOTE,
    ADB,
    BLUETOOTH;

    val protocol: TvProtocol
        get() = when (this) {
            ANDROID_TV_REMOTE -> TvProtocol.ANDROID_TV_REMOTE
            ADB -> TvProtocol.ADB
            BLUETOOTH -> TvProtocol.BLUETOOTH_HID
        }
}

data class TvEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val kind: TvEndpointKind,
    val model: String? = null
) {
    val key: String get() = "$host:$port:$kind"
    val protocol: TvProtocol get() = kind.protocol
}

data class SavedTv(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: TvProtocol,
    val method: ConnectionMethod,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isBluetooth: Boolean get() = protocol == TvProtocol.BLUETOOTH_HID

    fun toEndpoint(): TvEndpoint = TvEndpoint(
        name = name,
        host = host,
        port = port,
        kind = protocol.endpointKind,
        model = name
    )

    companion object {
        const val BLUETOOTH_HOST = "bluetooth"

        fun idFor(protocol: TvProtocol, host: String) = "${protocol.name}:$host"
    }
}

/**
 * One physical TV, possibly reachable through more than one protocol.
 * Saved devices remain selectable while they are temporarily offline.
 */
data class TvCandidate(
    val host: String,
    val name: String,
    val endpoints: List<TvEndpoint>,
    val saved: SavedTv?
) {
    val key: String get() = host
    val isBluetooth: Boolean
        get() = saved?.protocol == TvProtocol.BLUETOOTH_HID ||
            endpoints.any { it.protocol == TvProtocol.BLUETOOTH_HID }

    val protocols: List<TvProtocol>
        get() = buildList {
            endpoints.map { it.protocol }.distinct().forEach { add(it) }
            saved?.protocol?.takeIf { it !in this }?.let { add(it) }
        }

    fun endpoint(protocol: TvProtocol): TvEndpoint? =
        endpoints.firstOrNull { it.protocol == protocol }
            ?: saved?.takeIf { it.protocol == protocol }?.toEndpoint()

    /** Prefer Android TV Remote: it needs no ADB authorization and supports app launch. */
    fun preferredEndpoint(): TvEndpoint? =
        endpoint(TvProtocol.ANDROID_TV_REMOTE)
            ?: endpoint(TvProtocol.ADB)
            ?: endpoint(TvProtocol.BLUETOOTH_HID)

    fun statusLabel(): String = when {
        isBluetooth -> "BLUETOOTH"
        saved != null && endpoints.isEmpty() -> "SAVED"
        saved != null -> "SAVED · ${protocols.firstOrNull()?.shortTitle ?: "TV"}"
        else -> protocols.firstOrNull()?.shortTitle ?: "TV"
    }

    fun subtitle(): String = when {
        isBluetooth -> "Bluetooth HID"
        host.isBlank() -> "Локальная сеть"
        else -> host
    }
}

fun buildTvCandidates(
    endpoints: List<TvEndpoint>,
    saved: List<SavedTv>
): List<TvCandidate> {
    val byHost = LinkedHashMap<String, MutableList<TvEndpoint>>()
    endpoints.forEach { endpoint ->
        byHost.getOrPut(endpoint.host) { mutableListOf() } += endpoint
    }
    val savedByHost = saved.groupBy { it.host }
    val hosts = (byHost.keys + savedByHost.keys).toSet()

    return hosts.map { host ->
        val hostEndpoints = byHost[host].orEmpty()
            .distinctBy { it.key }
            .sortedBy { it.protocol.ordinal }
        val savedTv = savedByHost[host].orEmpty().maxByOrNull { it.updatedAt }
        val name = savedTv?.name?.takeIf { it.isNotBlank() }
            ?: hostEndpoints.firstOrNull { it.name.isNotBlank() }?.name
            ?: hostEndpoints.firstOrNull()?.model?.takeIf { it.isNotBlank() }
            ?: if (host == SavedTv.BLUETOOTH_HOST) "Bluetooth TV" else "TV $host"
        TvCandidate(host, name, hostEndpoints, savedTv)
    }.sortedWith(
        compareByDescending<TvCandidate> { it.saved != null }
            .thenByDescending { it.isBluetooth }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.host }
    )
}

/** Human-readable names for model codes used by Android TV firmware. */
object TvModelCatalog {
    private val friendlyModels = mapOf(
        // Xiaomi Mi TV 4A 32 (the priority test target).
        "MITV-MSSP0" to "Mi TV 4A 32"
    )

    fun displayName(manufacturer: String?, model: String?): String {
        val maker = manufacturer?.trim().orEmpty()
        val rawModel = model?.trim().orEmpty()
        if (rawModel.isBlank()) return maker.ifBlank { "Android TV" }
        val friendly = friendlyModels[rawModel.uppercase(Locale.ROOT)] ?: rawModel
        if (maker.isBlank() || friendly.startsWith(maker, ignoreCase = true)) return friendly
        return "$maker $friendly"
    }
}
