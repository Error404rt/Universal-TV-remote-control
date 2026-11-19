package com.universalremote.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.universalremote.app.connection.AdbRemoteClient
import com.universalremote.app.connection.AndroidTvRemotePhase
import com.universalremote.app.connection.AndroidTvRemoteV2
import com.universalremote.app.connection.BluetoothHidRemote
import com.universalremote.app.connection.ConnectionMethod
import com.universalremote.app.connection.HidConnectionState
import com.universalremote.app.connection.HotspotController
import com.universalremote.app.connection.MouseButton
import com.universalremote.app.connection.NavigationKey
import com.universalremote.app.connection.NetworkStatus
import com.universalremote.app.connection.NetworkStatusSnapshot
import com.universalremote.app.connection.NetworkUtils
import com.universalremote.app.connection.SavedTv
import com.universalremote.app.connection.SavedTvStore
import com.universalremote.app.connection.TvApp
import com.universalremote.app.connection.TvAppCatalog
import com.universalremote.app.connection.TvCandidate
import com.universalremote.app.connection.TvEndpoint
import com.universalremote.app.connection.TvEndpointKind
import com.universalremote.app.connection.TvIdentity
import com.universalremote.app.connection.TvProtocol
import com.universalremote.app.connection.TvDiscovery
import com.universalremote.app.connection.buildTvCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { UniversalRemoteApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UniversalRemoteApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adb = remember { AdbRemoteClient(context) }
    val discovery = remember { TvDiscovery(context) }
    val hid = remember { BluetoothHidRemote(context) }
    val tvRemote = remember { AndroidTvRemoteV2(context) }
    val savedStore = remember { SavedTvStore(context) }
    val preferences = remember {
        context.getSharedPreferences("universal_remote_ui", Context.MODE_PRIVATE)
    }

    val hidState by hid.stateFlow.collectAsState()
    val remoteState by tvRemote.stateFlow.collectAsState()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })

    var savedTvs by remember { mutableStateOf(savedStore.load()) }
    var endpoints by remember { mutableStateOf<List<TvEndpoint>>(emptyList()) }
    var selected by remember { mutableStateOf<TvCandidate?>(null) }
    var pendingMethod by remember { mutableStateOf<ConnectionMethod?>(null) }
    var activeMethod by remember { mutableStateOf<ConnectionMethod?>(null) }
    var pendingTarget by remember { mutableStateOf<TvEndpoint?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var tvIdentity by remember { mutableStateOf<TvIdentity?>(null) }
    var activeHost by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Готов к поиску телевизора") }
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var scanRequest by remember { mutableStateOf(0) }

    var showConnectSheet by remember { mutableStateOf(false) }
    var showPairingSheet by remember { mutableStateOf(false) }
    var pairTarget by remember { mutableStateOf<TvEndpoint?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var showManualIpSheet by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualProtocol by remember { mutableStateOf(TvProtocol.ANDROID_TV_REMOTE) }
    var showAppsSheet by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var appsNote by remember { mutableStateOf("") }
    var favoritePackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOnboarding by remember {
        mutableStateOf(!preferences.getBoolean(KEY_ONBOARDING_DONE, false))
    }
    var network by remember { mutableStateOf(NetworkStatus.snapshot(context)) }
    var toast by remember { mutableStateOf<String?>(null) }

    val candidates = remember(endpoints, savedTvs) { buildTvCandidates(endpoints, savedTvs) }
    val currentSelected = selected?.let { old ->
        candidates.firstOrNull { it.key == old.key } ?: old
    }
    val connected = adb.isConnected() ||
        hidState == HidConnectionState.CONNECTED ||
        remoteState.phase == AndroidTvRemotePhase.CONNECTED
    val displayedDevice = tvIdentity?.title
        ?: activeName.takeIf { it.isNotBlank() }
        ?: "Телевизор не подключён"
    val quickApps = remember(favoritePackages, installedApps) {
        TvAppCatalog.resolveSaved(favoritePackages, TvAppCatalog.merge(installedApps))
    }
    val pickerApps = remember(installedApps) { TvAppCatalog.merge(installedApps) }

    fun saveTv(endpoint: TvEndpoint, name: String, method: ConnectionMethod) {
        val protocol = endpoint.protocol
        val host = if (protocol == TvProtocol.BLUETOOTH_HID) SavedTv.BLUETOOTH_HOST else endpoint.host
        val port = if (protocol == TvProtocol.BLUETOOTH_HID) 0 else endpoint.port
        val saved = SavedTv(
            id = SavedTv.idFor(protocol, host),
            name = name.take(96),
            host = host,
            port = port,
            protocol = protocol,
            method = method
        )
        savedTvs = savedStore.upsert(saved)
    }

    fun beginBluetoothSession() {
        activeMethod = ConnectionMethod.BLUETOOTH
        status = "Включаю Bluetooth HID. На телевизоре: Bluetooth → Добавить устройство → " +
            "Universal Remote Control."
        scope.launch {
            beginBluetoothPairing(context, scope, hid) { message -> status = message }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            beginBluetoothSession()
        } else {
            status = "Нужны разрешения Bluetooth, чтобы телефон стал HID-пультом"
        }
    }

    fun startBluetooth() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            beginBluetoothSession()
        }
    }

    suspend fun connectAdbEndpoint(endpoint: TvEndpoint, method: ConnectionMethod, name: String) {
        status = "Подключаюсь к ${endpoint.host}:${endpoint.port} по ADB…"
        try {
            val identity = adb.connect(endpoint.host, endpoint.port)
            tvIdentity = identity
            activeHost = identity.host
            activeName = identity.title
            activeMethod = method
            saveTv(endpoint, identity.title, method)
            status = "Подключено: ${identity.title}"
            pendingTarget = null
            pendingName = ""
            pendingMethod = null
            pager.animateScrollToPage(1)
        } catch (error: Throwable) {
            adb.disconnect()
            activeMethod = null
            status = error.message?.take(180)
                ?: "Не удалось подключиться по ADB. Подтверди отладку на телевизоре."
        }
    }

    fun connectEndpoint(
        endpoint: TvEndpoint,
        method: ConnectionMethod,
        name: String,
        fallbackAdb: TvEndpoint? = null
    ) {
        selected = candidates.firstOrNull { it.host == endpoint.host }
            ?: TvCandidate(
                host = endpoint.host,
                name = name,
                endpoints = listOfNotNull(endpoint, fallbackAdb),
                saved = null
            )
        pendingTarget = endpoint
        pendingName = name
        pendingMethod = method
        activeMethod = method

        when (endpoint.kind) {
            TvEndpointKind.ANDROID_TV_REMOTE -> {
                if (hidState == HidConnectionState.CONNECTED) hid.disconnect()
                adb.disconnect()
                scope.launch {
                    if (connecting) return@launch
                    connecting = true
                    status = "Подключаюсь к ${endpoint.host} через Android TV Remote…"
                    try {
                        tvRemote.connect(endpoint.host)
                    } catch (error: Throwable) {
                        tvRemote.disconnect()
                        if (fallbackAdb != null) {
                            status = "Android TV Remote недоступен на этом телевизоре. Пробую ADB…"
                            connectAdbEndpoint(fallbackAdb, method, name)
                        } else {
                            activeMethod = null
                            status = error.message?.take(180)
                                ?: "Android TV Remote недоступен. Подключись по ADB или Bluetooth."
                        }
                    } finally {
                        connecting = false
                    }
                }
            }
            TvEndpointKind.ADB -> {
                if (hidState == HidConnectionState.CONNECTED) hid.disconnect()
                tvRemote.disconnect()
                scope.launch {
                    if (connecting) return@launch
                    connecting = true
                    try {
                        connectAdbEndpoint(endpoint, method, name)
                    } finally {
                        connecting = false
                    }
                }
            }
            TvEndpointKind.BLUETOOTH -> startBluetooth()
        }
    }

    fun connectCandidate(candidate: TvCandidate, method: ConnectionMethod) {
        selected = candidate
        pendingMethod = method
        activeMethod = method
        if (candidate.isBluetooth || method == ConnectionMethod.BLUETOOTH) {
            startBluetooth()
            return
        }
        val remote = candidate.endpoint(TvProtocol.ANDROID_TV_REMOTE)
        val adbEndpoint = candidate.endpoint(TvProtocol.ADB)
        val live = remote ?: adbEndpoint
        if (live != null) {
            connectEndpoint(
                live,
                method,
                candidate.name,
                fallbackAdb = adbEndpoint.takeIf { live.protocol == TvProtocol.ANDROID_TV_REMOTE }
            )
            return
        }
        status = "Проверяю ${candidate.host}…"
        scope.launch {
            if (connecting) return@launch
            connecting = true
            val probed = runCatching { discovery.probeHost(candidate.host) }
                .getOrDefault(emptyList())
            endpoints = mergeEndpoints(endpoints, probed)
            connecting = false
            val remoteEndpoint = probed.firstOrNull { it.protocol == TvProtocol.ANDROID_TV_REMOTE }
            val adbProbed = probed.firstOrNull { it.protocol == TvProtocol.ADB }
            val endpoint = remoteEndpoint ?: adbProbed
            if (endpoint == null) {
                status = "${candidate.name}: телевизор не отвечает. Включи его и проверь сеть."
            } else {
                connectEndpoint(
                    endpoint,
                    method,
                    candidate.name,
                    fallbackAdb = adbProbed.takeIf { endpoint.protocol == TvProtocol.ANDROID_TV_REMOTE }
                )
            }
        }
    }

    fun chooseMethod(method: ConnectionMethod) {
        showConnectSheet = false
        pendingMethod = method
        activeMethod = method
        network = NetworkStatus.snapshot(context)
        when (method) {
            ConnectionMethod.BLUETOOTH -> {
                startBluetooth()
            }
            ConnectionMethod.HOTSPOT -> {
                if (!network.hotspotEnabled) {
                    status = "Включи точку доступа телефона. Телевизор должен быть подключён к ней и сохранён в его сети."
                    HotspotController.openSettings(context)
                    scope.launch {
                        delay(6_000)
                        network = NetworkStatus.snapshot(context)
                        scanRequest += 1
                    }
                } else {
                    status = "Точка доступа включена (${network.hotspotAddress ?: "адрес не определён"}). Ищу телевизор…"
                }
                val target = currentSelected?.takeIf { !it.isBluetooth }
                if (target != null) {
                    connectCandidate(target, method)
                } else {
                    scanRequest += 1
                }
            }
            ConnectionMethod.WIFI -> {
                if (!network.wifiConnected) {
                    status = "Включи Wi-Fi и подключи телевизор к той же сети."
                    HotspotController.openWifiSettings(context)
                    scope.launch {
                        delay(6_000)
                        network = NetworkStatus.snapshot(context)
                        scanRequest += 1
                    }
                } else {
                    status = "Wi-Fi подключён (${network.wifiAddress ?: "адрес не определён"}). Ищу телевизор…"
                }
                val target = currentSelected?.takeIf { !it.isBluetooth }
                if (target != null) {
                    connectCandidate(target, method)
                } else {
                    scanRequest += 1
                }
            }
        }
    }

    fun sendKey(keyCode: Int) {
        scope.launch {
            try {
                when {
                    hidState == HidConnectionState.CONNECTED -> {
                        val key = when (keyCode) {
                            19 -> NavigationKey.UP
                            20 -> NavigationKey.DOWN
                            21 -> NavigationKey.LEFT
                            22 -> NavigationKey.RIGHT
                            23 -> NavigationKey.OK
                            4 -> NavigationKey.BACK
                            else -> null
                        }
                        when {
                            key != null -> hid.sendNavigationKey(key)
                            keyCode == 3 -> hid.sendConsumerUsage(0x0223)
                            keyCode == 82 -> hid.sendConsumerUsage(0x0040)
                        }
                    }
                    tvRemote.isConnected() -> tvRemote.sendKey(keyCode)
                    adb.isConnected() -> adb.sendKey(keyCode)
                    else -> status = "Сначала подключи телевизор"
                }
            } catch (error: Throwable) {
                status = error.message?.take(160) ?: "Команда не отправлена"
            }
        }
    }

    fun forgetActive() {
        adb.disconnect()
        hid.disconnect()
        tvRemote.disconnect()
        tvIdentity = null
        activeHost = null
        activeName = ""
        activeMethod = null
        pendingTarget = null
        pendingName = ""
        pendingMethod = null
        status = "Отключено"
    }

    fun removeSaved(candidate: TvCandidate) {
        val saved = candidate.saved ?: return
        savedTvs = savedStore.remove(saved.id)
        if (saved.protocol == TvProtocol.ANDROID_TV_REMOTE) tvRemote.forget(saved.host)
        if (selected?.key == candidate.key) selected = null
        if (activeHost == saved.host) forgetActive()
        status = "Устройство удалено из сохранённых"
    }

    fun launchTvApp(app: TvApp) {
        scope.launch {
            runCatching {
                when {
                    adb.isConnected() -> adb.launchApp(app)
                    tvRemote.isConnected() -> tvRemote.launchApp(app.packageName)
                    hidState == HidConnectionState.CONNECTED ->
                        error("Bluetooth-пульт не запускает приложения. Подключи телевизор по Wi-Fi или ADB.")
                    else -> error("Сначала подключи телевизор")
                }
            }.onFailure {
                status = it.message?.take(160) ?: "Не удалось запустить приложение"
            }
        }
    }

    fun openAppPicker() {
        if (activeHost == null) {
            status = "Сначала подключи телевизор, чтобы выбрать быстрые приложения"
            return
        }
        if (!adb.isConnected() && !tvRemote.isConnected()) {
            status = "Bluetooth-пульт не запускает приложения. Подключись по Wi-Fi или ADB."
            return
        }
        scope.launch {
            appsNote = when {
                adb.isConnected() -> "Приложения, установленные на телевизоре."
                else -> "Android TV Remote не отдаёт список приложений. Показаны популярные пакеты; " +
                    "запуск зависит от поддержки телевизора."
            }
            if (adb.isConnected()) {
                installedApps = runCatching { adb.installedTvApps() }.getOrDefault(emptyList())
            } else {
                installedApps = emptyList()
            }
            showAppsSheet = true
        }
    }

    fun toggleQuickApp(packageName: String) {
        val host = activeHost
        if (host == null) {
            status = "Сначала подключи телевизор, чтобы сохранить быстрые приложения"
            return
        }
        val next = when {
            packageName in favoritePackages -> favoritePackages - packageName
            favoritePackages.size >= 3 -> {
                status = "Можно выбрать не более трёх быстрых приложений"
                return
            }
            else -> favoritePackages + packageName
        }
        favoritePackages = next
        preferences.edit().putString(quickKey(host), next.joinToString(",")).apply()
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.close()
            adb.close()
            hid.disconnect()
            tvRemote.close()
        }
    }

    LaunchedEffect(scanRequest) {
        discovery.stop()
        endpoints = emptyList()
        scanning = true
        network = NetworkStatus.snapshot(context)
        status = "Ищу телевизоры в локальной сети…"
        discovery.start(scope) { endpoint ->
            endpoints = mergeEndpoints(endpoints, listOf(endpoint))
        }
        val savedHosts = savedTvs.map { it.host }
            .filter { NetworkUtils.isPrivateIpv4(it) }
            .distinct()
        val knownHosts = savedHosts.toSet()
        val found = withTimeoutOrNull(14_000) {
            val subnet = async {
                runCatching {
                    discovery.scanLocal(
                        knownHosts = knownHosts,
                        includeAdb = true,
                        connectTimeoutMs = 240
                    )
                }.getOrDefault(emptyList())
            }
            val savedProbes = savedHosts.map { host ->
                async {
                    runCatching {
                        discovery.probeHost(host, includeAdb = true, connectTimeoutMs = 550)
                    }.getOrDefault(emptyList())
                }
            }
            val nsdWindow = async { delay(2_600) }
            val subnetFound = subnet.await()
            nsdWindow.await()
            subnetFound + savedProbes.awaitAll().flatten()
        } ?: emptyList()
        endpoints = mergeEndpoints(endpoints, found)
        scanning = false
        val count = buildTvCandidates(endpoints, savedTvs).size
        status = if (count == 0) {
            "Телевизоры не найдены. Проверь сеть, включи телевизор или добавь адрес вручную."
        } else {
            "Найдено устройств: $count"
        }
    }

    LaunchedEffect(hidState) {
        when (hidState) {
            HidConnectionState.CONNECTED -> {
                val deviceName = runCatching { hid.connectedHost?.name }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Bluetooth TV"
                activeHost = SavedTv.BLUETOOTH_HOST
                activeName = deviceName
                activeMethod = ConnectionMethod.BLUETOOTH
                tvIdentity = null
                pendingTarget = null
                pendingName = ""
                pendingMethod = null
                saveTv(
                    TvEndpoint(deviceName, SavedTv.BLUETOOTH_HOST, 0, TvEndpointKind.BLUETOOTH),
                    deviceName,
                    ConnectionMethod.BLUETOOTH
                )
                status = "Bluetooth HID подключён: $deviceName"
                pager.animateScrollToPage(1)
            }
            HidConnectionState.WAITING_FOR_TV -> status =
                "На телевизоре открой Bluetooth → Добавить устройство → Universal Remote Control"
            HidConnectionState.ERROR -> status =
                "Bluetooth HID недоступен. Проверь Bluetooth и разрешения приложения."
            else -> Unit
        }
    }

    LaunchedEffect(remoteState.phase) {
        when (remoteState.phase) {
            AndroidTvRemotePhase.AWAITING_CODE -> {
                pairTarget = pendingTarget
                pairingCode = ""
                showPairingSheet = true
                status = remoteState.message
            }
            AndroidTvRemotePhase.CONNECTED -> {
                val host = remoteState.host
                val endpoint = pendingTarget ?: host?.let {
                    TvEndpoint("Android TV", it, TvProtocol.ANDROID_TV_REMOTE.defaultPort, TvEndpointKind.ANDROID_TV_REMOTE)
                }
                val name = pendingName.ifBlank {
                    currentSelected?.takeIf { it.host == host }?.name
                        ?: host
                        ?: "Android TV"
                }
                tvIdentity = null
                activeHost = host
                activeName = name
                activeMethod = pendingMethod ?: activeMethod ?: ConnectionMethod.WIFI
                endpoint?.let { saveTv(it, name, activeMethod ?: ConnectionMethod.WIFI) }
                pendingTarget = null
                pendingName = ""
                pendingMethod = null
                status = "Подключено: $name"
                pager.animateScrollToPage(1)
            }
            AndroidTvRemotePhase.ERROR -> status = remoteState.message
            else -> Unit
        }
    }

    LaunchedEffect(activeHost) {
        val host = activeHost ?: return@LaunchedEffect
        favoritePackages = preferences.getString(quickKey(host), null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(3)
            .orEmpty()
        installedApps = if (adb.isConnected()) {
            runCatching { adb.installedTvApps() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    LaunchedEffect(status) {
        if (status.isNotBlank()) {
            toast = status.take(170)
            delay(3_200)
            toast = null
        }
    }

    fun connectManual() {
        val host = manualIp.trim()
        if (!NetworkUtils.isPrivateIpv4(host)) {
            status = "Введи локальный IPv4-адрес телевизора"
            return
        }
        val endpoint = TvEndpoint(
            name = "TV $host",
            host = host,
            port = manualProtocol.defaultPort,
            kind = manualProtocol.endpointKind
        )
        showManualIpSheet = false
        connectEndpoint(endpoint, pendingMethod ?: ConnectionMethod.WIFI, "TV $host")
    }

    Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> MousePage(
                    connected = connected,
                    bluetooth = hidState == HidConnectionState.CONNECTED,
                    connectionMethod = activeMethod,
                    hint = when {
                        hidState == HidConnectionState.CONNECTED ->
                            "Движение — курсор, касание — клик, удержание — перетаскивание"
                        adb.isConnected() ->
                            "Касание — tap, свайп — swipe, удержание — long press"
                        remoteState.phase == AndroidTvRemotePhase.CONNECTED ->
                            "Свайп — навигация по фокусу, тап — OK, удержание — долгое нажатие"
                        else -> null
                    },
                    onBluetoothMove = { dx, dy ->
                        scope.launch { runCatching { hid.moveMouse(dx * 1.7f, dy * 1.7f) } }
                    },
                    onBluetoothHoldStart = {
                        scope.launch { runCatching { hid.mouseButton(MouseButton.LEFT, true) } }
                    },
                    onBluetoothHoldEnd = {
                        scope.launch { runCatching { hid.mouseButton(MouseButton.LEFT, false) } }
                    },
                    onBluetoothTap = {
                        scope.launch { runCatching { hid.click(MouseButton.LEFT) } }
                    },
                    onTap = { x, y ->
                        scope.launch {
                            when {
                                adb.isConnected() -> runCatching { adb.tapFraction(x, y) }
                                tvRemote.isConnected() -> runCatching { tvRemote.sendKey(23) }
                                else -> status = "Сначала подключи телевизор"
                            }
                        }
                    },
                    onLongPress = { x, y ->
                        scope.launch {
                            when {
                                adb.isConnected() -> runCatching { adb.swipeFraction(x, y, x, y, 800) }
                                tvRemote.isConnected() -> runCatching {
                                    tvRemote.sendKeyDown(23)
                                    delay(700)
                                    tvRemote.sendKeyUp(23)
                                }
                                else -> status = "Сначала подключи телевизор"
                            }
                        }
                    },
                    onSwipe = { x1, y1, x2, y2, duration ->
                        scope.launch {
                            when {
                                adb.isConnected() -> runCatching {
                                    adb.swipeFraction(x1, y1, x2, y2, duration)
                                }
                                tvRemote.isConnected() -> {
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    val keyCode = if (abs(dx) > abs(dy)) {
                                        if (dx > 0.035f) 22 else if (dx < -0.035f) 21 else null
                                    } else {
                                        if (dy > 0.035f) 20 else if (dy < -0.035f) 19 else null
                                    }
                                    if (keyCode != null) runCatching { tvRemote.sendKey(keyCode) }
                                }
                                else -> status = "Сначала подключи телевизор"
                            }
                        }
                    },
                    onRightClick = {
                        scope.launch {
                            if (hidState == HidConnectionState.CONNECTED) {
                                runCatching { hid.click(MouseButton.RIGHT) }
                            } else {
                                sendKey(4)
                            }
                        }
                    },
                    onBack = { sendKey(4) }
                )
                1 -> RemotePage(
                    deviceName = displayedDevice,
                    connected = connected,
                    connectionMethod = activeMethod,
                    apps = quickApps,
                    onKey = ::sendKey,
                    onApp = ::launchTvApp,
                    onAddApps = ::openAppPicker
                )
                else -> DevicesPage(
                    connected = connected,
                    deviceName = displayedDevice,
                    connectionMethod = activeMethod,
                    candidates = candidates,
                    selected = currentSelected,
                    scanning = scanning,
                    connecting = connecting,
                    status = status,
                    onRefresh = {
                        network = NetworkStatus.snapshot(context)
                        scanRequest += 1
                    },
                    onConnect = {
                        network = NetworkStatus.snapshot(context)
                        showConnectSheet = true
                    },
                    onManualIp = { showManualIpSheet = true },
                    onSelect = { candidate ->
                        val method = candidate.saved?.method ?: pendingMethod ?: ConnectionMethod.WIFI
                        connectCandidate(candidate, method)
                    },
                    onRemoveSaved = ::removeSaved,
                    onForget = ::forgetActive
                )
            }
        }

        if (showConnectSheet) {
            ConnectMethodSheet(
                network = network,
                onClose = { showConnectSheet = false },
                onSelect = ::chooseMethod
            )
        }
        if (showPairingSheet && pairTarget != null) {
            PairingCodeSheet(
                host = pairTarget!!.host,
                code = pairingCode,
                onCode = {
                    pairingCode = it.filter { char ->
                        char.isDigit() || char.lowercaseChar() in 'a'..'f'
                    }.take(6).uppercase()
                },
                onClose = {
                    showPairingSheet = false
                    tvRemote.disconnect()
                    pendingTarget = null
                    pendingName = ""
                    pendingMethod = null
                },
                onPair = {
                    val code = pairingCode
                    showPairingSheet = false
                    scope.launch {
                        if (connecting) return@launch
                        connecting = true
                        status = "Сопрягаю Android TV Remote…"
                        try {
                            tvRemote.pair(code)
                        } catch (error: Throwable) {
                            status = error.message?.take(180) ?: "Сопряжение не удалось"
                        } finally {
                            connecting = false
                        }
                    }
                }
            )
        }
        if (showManualIpSheet) {
            ManualIpSheet(
                value = manualIp,
                protocol = manualProtocol,
                onValue = { manualIp = it.filter { char -> char.isDigit() || char == '.' }.take(15) },
                onProtocol = { manualProtocol = it },
                onClose = { showManualIpSheet = false },
                onConnect = ::connectManual
            )
        }
        if (showAppsSheet) {
            AppPickerSheet(
                apps = pickerApps,
                selected = favoritePackages,
                note = appsNote,
                onToggle = ::toggleQuickApp,
                onClose = { showAppsSheet = false }
            )
        }
        if (showOnboarding) {
            OnboardingSheet {
                preferences.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                showOnboarding = false
            }
        }
        if (toast != null && pager.currentPage != 2 && !showOnboarding &&
            !showConnectSheet && !showPairingSheet && !showManualIpSheet && !showAppsSheet
        ) {
            StatusPill(
                text = toast.orEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp, start = 30.dp, end = 30.dp)
            )
        }
    }
}

private fun mergeEndpoints(
    current: List<TvEndpoint>,
    additions: List<TvEndpoint>
): List<TvEndpoint> = (current + additions)
    .distinctBy { it.key }
    .sortedWith(compareBy<TvEndpoint> { it.host }.thenBy { it.kind.ordinal })

private fun quickKey(host: String): String = "quick_apps:$host"

private fun beginBluetoothPairing(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    hid: BluetoothHidRemote,
    onStatus: (String) -> Unit
) {
    scope.launch {
        val ready = runCatching { hid.registerForTvPairing() }.getOrDefault(false)
        if (!ready) {
            onStatus("Телефон не смог включить Bluetooth HID. Проверь Bluetooth и разрешения.")
            return@launch
        }
        onStatus("На телевизоре открой Bluetooth → Добавить устройство → Universal Remote Control")
        runCatching {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            onStatus("Не удалось включить обнаружение Bluetooth. Открой настройки Bluetooth телефона.")
        }
    }
}

private const val KEY_ONBOARDING_DONE = "onboarding_done_v2"
