package com.universalremote.app.connection

import android.content.Context
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TvIdentity(
    val host: String,
    val port: Int,
    val manufacturer: String,
    val model: String,
    val androidVersion: String
) {
    val title: String
        get() = TvModelCatalog.displayName(manufacturer, model)
}

data class TvApp(
    val packageName: String,
    val displayName: String,
    val componentName: String? = null
)

class AdbRemoteClient(context: Context) : AutoCloseable {
    @Volatile private var client: Kadb? = null
    @Volatile private var connected = false
    @Volatile var identity: TvIdentity? = null
        private set
    @Volatile private var cachedDisplaySize: Pair<Int, Int>? = null
    @Volatile private var componentByPackage: Map<String, String?> = emptyMap()

    init {
        AdbIdentity.configure(context.applicationContext)
    }

    /**
     * Connects only to a literal private IPv4 address. A successful ADB shell handshake
     * is required before the device is presented as connected.
     */
    suspend fun connect(host: String, port: Int): TvIdentity = withContext(Dispatchers.IO) {
        require(NetworkUtils.isPrivateIpv4(host)) {
            "Разрешено подключение только к устройству в локальной сети"
        }
        require(port in 1..65535)

        disconnect()
        val candidate = Kadb.create(host, port, connectTimeout = 2500, socketTimeout = 5000)
        try {
            val model = shell(candidate, "getprop ro.product.model").trim().take(80)
            val manufacturer = shell(candidate, "getprop ro.product.manufacturer").trim().take(80)
            val version = shell(candidate, "getprop ro.build.version.release").trim().take(32)
            val result = TvIdentity(
                host = host,
                port = port,
                manufacturer = manufacturer,
                model = model,
                androidVersion = version
            )
            val display = runCatching { readDisplaySize(candidate) }.getOrNull()
            val components = runCatching { queryComponents(candidate) }.getOrDefault(emptyMap())

            client = candidate
            connected = true
            identity = result
            cachedDisplaySize = display
            componentByPackage = components
            result
        } catch (error: Throwable) {
            candidate.close()
            throw friendlyError(error)
        }
    }

    suspend fun pairWireless(host: String, port: Int, pairingCode: String) = withContext(Dispatchers.IO) {
        require(NetworkUtils.isPrivateIpv4(host)) { "Разрешено сопряжение только в локальной сети" }
        require(port in 1..65535)
        require(pairingCode.matches(Regex("[0-9]{6}"))) { "Код сопряжения должен содержать 6 цифр" }
        Kadb.pair(host, port, pairingCode)
    }

    suspend fun sendKey(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        require(androidKeyCode in 0..300)
        shell(active(), "input keyevent $androidKeyCode")
        Unit
    }

    suspend fun displaySize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        cachedDisplaySize?.let { return@withContext it }
        val size = readDisplaySize(active())
        cachedDisplaySize = size
        size
    }

    suspend fun tapFraction(x: Float, y: Float) = withContext(Dispatchers.IO) {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val (width, height) = displaySizeNow()
        val screenX = (x * (width - 1)).toInt()
        val screenY = (y * (height - 1)).toInt()
        shell(active(), "input tap $screenX $screenY")
        Unit
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
        val (width, height) = displaySizeNow()
        val x1 = (startX * (width - 1)).toInt()
        val y1 = (startY * (height - 1)).toInt()
        val x2 = (endX * (width - 1)).toInt()
        val y2 = (endY * (height - 1)).toInt()
        shell(active(), "input swipe $x1 $y1 $x2 $y2 $durationMs")
        Unit
    }

    suspend fun tap(x: Int, y: Int, screenWidth: Int, screenHeight: Int) = withContext(Dispatchers.IO) {
        require(x in 0..screenWidth && y in 0..screenHeight)
        require(screenWidth in 1..10000 && screenHeight in 1..10000)
        shell(active(), "input tap $x $y")
        Unit
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
        shell(active(), "input swipe $startX $startY $endX $endY $durationMs")
        Unit
    }

    /**
     * Returns launchable TV activities. Leanback activities are preferred; the
     * fallback category and third-party package list keep older firmwares usable.
     */
    suspend fun installedTvApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val components = queryComponents(active())
        componentByPackage = components
        components.map { (packageName, component) ->
            TvApp(
                packageName = packageName,
                displayName = TvAppCatalog.knownName(packageName)
                    ?: humanizePackage(packageName),
                componentName = component
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    suspend fun launchApp(app: TvApp) = withContext(Dispatchers.IO) {
        launchPackage(app.packageName, app.componentName ?: componentByPackage[app.packageName])
    }

    suspend fun launchApp(packageName: String) = withContext(Dispatchers.IO) {
        launchPackage(packageName, componentByPackage[packageName])
    }

    fun isConnected(): Boolean = connected && client != null

    fun disconnect() {
        val previous = client
        client = null
        connected = false
        identity = null
        cachedDisplaySize = null
        componentByPackage = emptyMap()
        runCatching { previous?.close() }
    }

    override fun close() = disconnect()

    private fun active(): Kadb = client ?: error("Сначала подключись к телевизору")

    private fun displaySizeNow(): Pair<Int, Int> {
        cachedDisplaySize?.let { return it }
        val size = readDisplaySize(active())
        cachedDisplaySize = size
        return size
    }

    private fun readDisplaySize(target: Kadb): Pair<Int, Int> {
        val output = shell(target, "wm size")
        val matches = Regex("([0-9]{2,5})x([0-9]{2,5})").findAll(output).toList()
        val match = matches.lastOrNull() ?: error("Не удалось определить размер экрана телевизора")
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        require(width in 1..10000 && height in 1..10000)
        return width to height
    }

    private fun shell(target: Kadb, command: String): String =
        target.shell(command).allOutput

    private fun queryComponents(target: Kadb): Map<String, String?> {
        val output = listOf(
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER",
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        ).firstNotNullOfOrNull { command ->
            runCatching { shell(target, command) }
                .getOrDefault("")
                .takeIf { it.isNotBlank() && !it.contains("Error", true) }
        }

        val components = parseComponentLines(output.orEmpty())
        if (components.isNotEmpty()) return components

        val packages = runCatching { shell(target, "pm list packages -3") }.getOrDefault("")
        return parsePackageLines(packages)
    }

    private fun parseComponentLines(output: String): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        output.lineSequence().forEach { line ->
            val component = parseComponent(extractComponentText(line)) ?: return@forEach
            val packageName = component.substringBefore('/')
            result.putIfAbsent(packageName, component)
        }
        return result
    }

    private fun parsePackageLines(output: String): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        output.lineSequence().forEach { line ->
            val value = line.trim().removePrefix("package:")
            if (value.matches(PACKAGE_PATTERN)) result.putIfAbsent(value, null)
        }
        return result
    }

    private fun extractComponentText(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ""
        val inside = trimmed.substringAfter("ComponentInfo{", missingDelimiterValue = "")
        if (inside.isNotEmpty()) return inside.substringBefore('}')
        return trimmed
    }

    private fun parseComponent(value: String): String? {
        val match = COMPONENT_PATTERN.find(value) ?: return null
        val packageName = match.groupValues[1]
        val rawActivity = match.groupValues[2]
        if (!packageName.matches(PACKAGE_PATTERN)) return null
        val activity = if (rawActivity.startsWith('.')) packageName + rawActivity else rawActivity
        if (!activity.matches(ACTIVITY_PATTERN)) return null
        return "$packageName/$activity"
    }

    private fun launchPackage(packageName: String, component: String?) {
        require(packageName.matches(PACKAGE_PATTERN)) { "Некорректный package ID" }
        val validComponent = component
            ?.takeIf { it.matches(COMPONENT_PATTERN_VALUE) }

        if (validComponent != null) {
            val output = shell(active(), "am start -n $validComponent")
            if (!isLaunchFailure(output)) return
        }

        val monkey = shell(
            active(),
            "monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1"
        )
        if (!isLaunchFailure(monkey)) return

        val resolved = listOf("LEANBACK_LAUNCHER", "LAUNCHER").firstNotNullOfOrNull { category ->
            val output = shell(
                active(),
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.$category $packageName"
            )
            output.lineSequence()
                .mapNotNull { parseComponent(it) }
                .lastOrNull()
        }
        if (resolved != null) {
            val output = shell(active(), "am start -n $resolved")
            if (!isLaunchFailure(output)) return
        }
        error("Не удалось запустить приложение на телевизоре")
    }

    private fun isLaunchFailure(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("no activities found") ||
            lower.contains("monkey aborted") ||
            lower.contains("error:") ||
            lower.contains("exception") ||
            lower.contains("not found") ||
            lower.contains("does not exist")
    }

    private fun friendlyError(error: Throwable): Throwable {
        val raw = error.message.orEmpty()
        val message = when {
            raw.contains("unauthorized", true) ||
                raw.contains("authentication", true) ||
                raw.contains("auth", true) -> {
                "Телевизор не разрешил отладку. Подтверди запрос «Разрешена отладка по USB?» на экране телевизора."
            }
            raw.contains("timed out", true) || raw.contains("timeout", true) -> {
                "Телевизор не ответил по ADB. Проверь сеть и включённую отладку."
            }
            else -> return error
        }
        return IllegalStateException(message, error)
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

    companion object {
        private val PACKAGE_PATTERN =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val ACTIVITY_PATTERN = Regex("[A-Za-z0-9_.$]+")
        private val COMPONENT_PATTERN =
            Regex("([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)/([A-Za-z0-9_.$]+)")
        private val COMPONENT_PATTERN_VALUE =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")
    }
}
