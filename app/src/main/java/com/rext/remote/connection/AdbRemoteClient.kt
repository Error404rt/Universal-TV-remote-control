package com.rext.remote.connection

import android.content.Context
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

data class TvIdentity(
    val host: String,
    val port: Int,
    val manufacturer: String,
    val model: String,
    val androidVersion: String
) {
    val title: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ")
            .ifBlank { "Android TV" }
}

data class TvApp(
    val packageName: String,
    val displayName: String
)

class AdbRemoteClient(context: Context) : AutoCloseable {
    @Volatile private var client: Kadb? = null
    @Volatile var identity: TvIdentity? = null
        private set

    init {
        AdbIdentity.configure(context.applicationContext)
    }

    /**
     * Connects only to a literal private IPv4 address. A successful ADB shell handshake
     * is required before the device is presented as connected.
     */
    suspend fun connect(host: String, port: Int): TvIdentity = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Разрешено подключение только к устройству в локальной сети" }
        require(port in 1..65535)

        disconnect()
        val candidate = Kadb.create(host, port, connectTimeout = 1800, socketTimeout = 3500)
        try {
            val model = candidate.shell("getprop ro.product.model").allOutput.trim().take(80)
            val manufacturer = candidate.shell("getprop ro.product.manufacturer").allOutput.trim().take(80)
            val version = candidate.shell("getprop ro.build.version.release").allOutput.trim().take(32)
            val result = TvIdentity(
                host = host,
                port = port,
                manufacturer = manufacturer,
                model = model,
                androidVersion = version
            )
            client = candidate
            identity = result
            result
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    suspend fun pairWireless(host: String, port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Разрешено сопряжение только в локальной сети" }
        require(port in 1..65535)
        require(pairingCode.matches(Regex("[0-9]{6}"))) { "Код сопряжения должен содержать 6 цифр" }
        Kadb.pair(host, port, pairingCode)
    }

    suspend fun sendKey(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        require(androidKeyCode in 0..300)
        active().shell("input keyevent $androidKeyCode")
    }

    suspend fun displaySize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val output = active().shell("wm size").allOutput
        val matches = Regex("([0-9]{2,5})x([0-9]{2,5})").findAll(output).toList()
        val match = matches.lastOrNull() ?: error("Не удалось определить размер экрана телевизора")
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        require(width in 1..10000 && height in 1..10000)
        width to height
    }

    suspend fun tapFraction(x: Float, y: Float) = withContext(Dispatchers.IO) {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val (width, height) = displaySize()
        val screenX = (x * (width - 1)).toInt()
        val screenY = (y * (height - 1)).toInt()
        active().shell("input tap $screenX $screenY")
    }

    suspend fun swipeFraction(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 350
    ) = withContext(Dispatchers.IO) {
        val points = listOf(startX, startY, endX, endY)
        require(points.all { it.isFinite() && it in 0f..1f })
        require(durationMs in 1..10000)
        val (width, height) = displaySize()
        val x1 = (startX * (width - 1)).toInt()
        val y1 = (startY * (height - 1)).toInt()
        val x2 = (endX * (width - 1)).toInt()
        val y2 = (endY * (height - 1)).toInt()
        active().shell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    suspend fun tap(x: Int, y: Int, screenWidth: Int, screenHeight: Int) = withContext(Dispatchers.IO) {
        require(x in 0..screenWidth && y in 0..screenHeight)
        require(screenWidth in 1..10000 && screenHeight in 1..10000)
        active().shell("input tap $x $y")
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        require(durationMs in 1..10000)
        require(listOf(startX, endX).all { it in 0..screenWidth })
        require(listOf(startY, endY).all { it in 0..screenHeight })
        require(screenWidth in 1..10000 && screenHeight in 1..10000)
        active().shell("input swipe $startX $startY $endX $endY $durationMs")
    }

    /**
     * Returns launchable Leanback activities. Package names are kept separately from
     * display labels; labels unknown to the TV shell are rendered as readable package names.
     */
    suspend fun installedTvApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val output = active().shell(
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
        ).allOutput
        output.lineSequence()
            .mapNotNull(::packageFromActivityLine)
            .distinct()
            .map { packageName -> TvApp(packageName, knownAppName(packageName) ?: humanizePackage(packageName)) }
            .sortedBy { it.displayName.lowercase() }
            .take(120)
            .toList()
    }

    suspend fun launchApp(packageName: String) = withContext(Dispatchers.IO) {
        require(packageName.matches(PACKAGE_PATTERN)) { "Некорректный package ID" }
        active().shell(
            "monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1"
        )
    }

    fun isConnected(): Boolean = client?.connectionCheck() == true

    fun disconnect() {
        val previous = client
        client = null
        identity = null
        runCatching { previous?.close() }
    }

    override fun close() = disconnect()

    private fun active(): Kadb = client ?: error("Сначала подключись к телевизору")

    private fun packageFromActivityLine(line: String): String? {
        val value = line.trim()
        if (value.isBlank() || value.startsWith("No activities", true)) return null
        val component = value.substringAfter("ComponentInfo{", value).substringBefore('}')
        val packageName = component.substringBefore('/')
        return packageName.takeIf { it.matches(PACKAGE_PATTERN) }
    }

    private fun humanizePackage(packageName: String): String {
        val tail = packageName.substringAfterLast('.')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
        return tail.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .ifBlank { packageName }
            .take(32)
    }

    private fun knownAppName(packageName: String): String? = when (packageName) {
        "com.google.android.youtube.tv", "com.google.android.youtube" -> "YouTube"
        "com.netflix.ninja" -> "Netflix"
        "com.amazon.amazonvideo.livingroom" -> "Prime Video"
        "com.disney.disneyplus" -> "Disney+"
        "com.spotify.tv.android" -> "Spotify"
        "com.google.android.tvlauncher" -> "Google TV"
        "com.android.vending" -> "Play Store"
        else -> null
    }

    private fun isPrivateIpv4(value: String): Boolean = runCatching {
        val address = InetAddress.getByName(value)
        address is Inet4Address &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            address.isSiteLocalAddress &&
            address.hostAddress == value
    }.getOrDefault(false)

    companion object {
        private val PACKAGE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
