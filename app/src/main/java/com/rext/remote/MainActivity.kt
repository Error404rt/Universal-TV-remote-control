package com.rext.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.pager.rememberPagerState
import com.rext.remote.connection.AdbDiscovery
import com.rext.remote.connection.AdbEndpoint
import com.rext.remote.connection.AdbEndpointKind
import com.rext.remote.connection.AdbRemoteClient
import com.rext.remote.connection.BluetoothHidRemote
import com.rext.remote.connection.HidConnectionState
import com.rext.remote.connection.MouseButton
import com.rext.remote.connection.MiLinkRemote
import com.rext.remote.connection.NavigationKey
import com.rext.remote.connection.TvApp
import com.rext.remote.connection.TvIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Ink = Color.Black
private val Paper = Color.White
private val Dim = Color(0xFF8F8F8F)
private val Hairline = Color(0xFF555555)

private enum class ConnectionMethod(val title: String, val hint: String) {
    BLUETOOTH("Bluetooth", "Телефон работает как HID-пульт и мышь"),
    HOTSPOT("Phone hotspot", "Телевизор подключается к точке доступа телефона"),
    WIFI("Shared Wi-Fi", "Телефон и телевизор находятся в одной сети")
}

private enum class Glyph {
    BLUETOOTH, PHONE, WIFI, REMOTE, MOUSE, HOME, MENU, BACK, PLUS, TV, YOUTUBE,
    REFRESH, KEYBOARD, LEFT_CLICK, RIGHT_CLICK, CHECK, IP, CLOSE, POINTER
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RextRemoteApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RextRemoteApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adb = remember { AdbRemoteClient(context) }
    val discovery = remember { AdbDiscovery(context) }
    val hid = remember { BluetoothHidRemote(context) }
    val hidState by hid.stateFlow.collectAsState()
    val miLink = remember { MiLinkRemote() }
    val miLinkConnected by miLink.stateFlow.collectAsState()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })

    var endpoints by remember { mutableStateOf<List<AdbEndpoint>>(emptyList()) }
    var selectedEndpoint by remember { mutableStateOf<AdbEndpoint?>(null) }
    var pendingMethod by remember { mutableStateOf<ConnectionMethod?>(null) }
    var activeMethod by remember { mutableStateOf<ConnectionMethod?>(null) }
    var tvIdentity by remember { mutableStateOf<TvIdentity?>(null) }
    var tvDisplayName by remember { mutableStateOf("") }
    var connectionStatus by remember { mutableStateOf("Готов к поиску телевизора") }
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var scanRequest by remember { mutableStateOf(0) }

    var showConnectSheet by remember { mutableStateOf(false) }
    var showPairingSheet by remember { mutableStateOf(false) }
    var pairTarget by remember { mutableStateOf<AdbEndpoint?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var showManualIpSheet by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var showAppsSheet by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var favoritePackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val preferences = remember { context.getSharedPreferences("rext_remote_ui", Context.MODE_PRIVATE) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            beginBluetoothPairing(context, scope, hid) { connectionStatus = it }
        } else {
            connectionStatus = "Нужны разрешения Bluetooth для режима HID"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.close()
            adb.close()
            hid.disconnect()
            miLink.close()
        }
    }

    LaunchedEffect(scanRequest) {
        endpoints = emptyList()
        scanning = true
        discovery.start(scope) { endpoint ->
            endpoints = (endpoints.filterNot { it.key == endpoint.key } + endpoint)
                .sortedWith(compareBy<AdbEndpoint> { it.host }.thenBy { it.kind.ordinal })
        }
        val classic = runCatching { discovery.scanClassicAdb() }.getOrDefault(emptyList())
        val miLinkDevices = runCatching { discovery.scanMiLink() }.getOrDefault(emptyList())
        endpoints = (endpoints + classic + miLinkDevices)
            .distinctBy { it.key }
            .sortedWith(compareBy<AdbEndpoint> { it.host }.thenBy { it.kind.ordinal })
        scanning = false
        if (endpoints.isEmpty()) {
            connectionStatus = "Устройства пока не найдены. Проверь сеть и ADB на телевизоре."
        } else {
            connectionStatus = "Найдено сетевых ADB-точек: ${endpoints.size}"
        }
    }

    LaunchedEffect(hidState) {
        when (hidState) {
            HidConnectionState.CONNECTED -> {
                activeMethod = ConnectionMethod.BLUETOOTH
                pendingMethod = null
                tvIdentity = null
                tvDisplayName = hid.connectedHost?.name?.takeIf { it.isNotBlank() } ?: "Bluetooth TV"
                connectionStatus = "Bluetooth HID подключён"
                pager.animateScrollToPage(1)
            }
            HidConnectionState.WAITING_FOR_TV -> connectionStatus =
                "Открой Bluetooth на телевизоре и выбери Universal Remote Control"
            HidConnectionState.ERROR -> connectionStatus = "Bluetooth HID недоступен. Проверь сопряжение и разрешения."
            else -> Unit
        }
    }

    LaunchedEffect(tvIdentity?.host) {
        val host = tvIdentity?.host ?: return@LaunchedEffect
        val key = "quick_apps:$host"
        favoritePackages = preferences.getStringSet(key, emptySet())?.toSet() ?: emptySet()
        installedApps = runCatching { adb.installedTvApps() }.getOrDefault(emptyList())
        if (favoritePackages.isEmpty()) {
            val youtube = installedApps.firstOrNull { it.packageName == "com.google.android.youtube.tv" }
            if (youtube != null) {
                favoritePackages = setOf(youtube.packageName)
                preferences.edit().putStringSet(key, favoritePackages).apply()
            }
        }
    }

    fun persistFavorites(newValue: Set<String>) {
        favoritePackages = newValue
        val host = tvIdentity?.host ?: return
        preferences.edit().putStringSet("quick_apps:$host", newValue).apply()
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
            beginBluetoothPairing(context, scope, hid) { connectionStatus = it }
        }
    }

    fun connectEndpoint(endpoint: AdbEndpoint, method: ConnectionMethod) {
        selectedEndpoint = endpoint
        pendingMethod = method
        if (endpoint.kind == AdbEndpointKind.MI_LINK) {
            scope.launch {
                connecting = true
                connectionStatus = "Подключаюсь к Xiaomi TV через MiLink…"
                try {
                    miLink.connect(endpoint.host, endpoint.port)
                    tvIdentity = null
                    tvDisplayName = endpoint.model?.takeIf { it.isNotBlank() }
                        ?: endpoint.name.ifBlank { "Xiaomi Mi TV" }
                    activeMethod = method
                    pendingMethod = null
                    connectionStatus = "Подключено: $tvDisplayName"
                    pager.animateScrollToPage(1)
                } catch (error: Throwable) {
                    connectionStatus = error.message?.take(140)
                        ?: "MiLink недоступен. Проверь, что телевизор в той же сети."
                    miLink.disconnect()
                } finally {
                    connecting = false
                }
            }
            return
        }
        if (endpoint.kind == AdbEndpointKind.TLS_PAIRING) {
            pairTarget = endpoint
            pairingCode = ""
            showPairingSheet = true
            connectionStatus = "Введи код из окна Wireless debugging на телевизоре"
            return
        }
        scope.launch {
            connecting = true
            connectionStatus = "Подключаюсь к ${endpoint.host}:${endpoint.port}…"
            try {
                val identity = adb.connect(endpoint.host, endpoint.port)
                tvIdentity = identity
                tvDisplayName = identity.title
                activeMethod = method
                pendingMethod = null
                connectionStatus = "Подключено: ${identity.title}"
                pager.animateScrollToPage(1)
            } catch (error: Throwable) {
                connectionStatus = error.message?.take(120)
                    ?: "Не удалось подключиться. Проверь ADB и подтверждение на телевизоре."
                adb.disconnect()
            } finally {
                connecting = false
            }
        }
    }

    fun chooseMethod(method: ConnectionMethod) {
        showConnectSheet = false
        pendingMethod = method
        when (method) {
            ConnectionMethod.BLUETOOTH -> {
                pendingMethod = null
                connectionStatus = "Телефон готов как Bluetooth HID-пульт. На телевизоре открой Bluetooth → Добавить устройство → выбери Universal Remote Control."
                startBluetooth()
            }
            ConnectionMethod.HOTSPOT, ConnectionMethod.WIFI -> {
                connectionStatus = if (method == ConnectionMethod.HOTSPOT) {
                    "Включи точку доступа. Телевизор должен сам подключиться к сохранённой сети."
                } else {
                    "Убедись, что телефон и телевизор подключены к одной Wi-Fi сети."
                }
                if (selectedEndpoint != null) {
                    connectEndpoint(selectedEndpoint!!, method)
                } else {
                    scanRequest += 1
                }
            }
        }
    }

    fun sendKey(keyCode: Int) {
        scope.launch {
            try {
                if (hidState == HidConnectionState.CONNECTED) {
                    val key = when (keyCode) {
                        19 -> NavigationKey.UP
                        20 -> NavigationKey.DOWN
                        21 -> NavigationKey.LEFT
                        22 -> NavigationKey.RIGHT
                        23 -> NavigationKey.OK
                        4 -> NavigationKey.BACK
                        else -> null
                    }
                    if (key != null) hid.sendNavigationKey(key)
                    else if (keyCode == 3) hid.sendConsumerUsage(0x0223)
                    else if (keyCode == 82) hid.sendConsumerUsage(0x0040)
                } else if (adb.isConnected()) {
                    adb.sendKey(keyCode)
                } else if (miLink.isConnected()) {
                    miLink.sendKey(keyCode)
                } else {
                    connectionStatus = "Сначала подключи телевизор"
                }
            } catch (error: Throwable) {
                connectionStatus = error.message?.take(120) ?: "Команда не отправлена"
            }
        }
    }

    fun launchTvApp(app: TvApp) {
        scope.launch {
            runCatching { adb.launchApp(app.packageName) }
                .onFailure { connectionStatus = it.message?.take(120) ?: "Не удалось запустить приложение" }
        }
    }

    val connected = adb.isConnected() || hidState == HidConnectionState.CONNECTED || miLinkConnected
    val displayedDevice = tvIdentity?.title ?: tvDisplayName.ifBlank { "Телевизор не подключён" }
    val currentPage = pager.currentPage

    Box(Modifier.fillMaxSize().background(Ink)) {
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
                    onAdbTap = { x, y ->
                        scope.launch {
                            when {
                                adb.isConnected() -> runCatching { adb.tapFraction(x, y) }
                                miLink.isConnected() -> runCatching { miLink.sendKey(23) }
                            }
                        }
                    },
                    onAdbSwipe = { x1, y1, x2, y2, duration ->
                        scope.launch {
                            when {
                                adb.isConnected() -> runCatching { adb.swipeFraction(x1, y1, x2, y2, duration) }
                                miLink.isConnected() -> {
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    val keyCode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                        if (dx > 0.035f) 22 else if (dx < -0.035f) 21 else null
                                    } else {
                                        if (dy > 0.035f) 20 else if (dy < -0.035f) 19 else null
                                    }
                                    if (keyCode != null) runCatching { miLink.sendKey(keyCode) }
                                }
                            }
                        }
                    },
                    onRightClick = {
                        scope.launch {
                            if (hidState == HidConnectionState.CONNECTED) hid.click(MouseButton.RIGHT)
                            else sendKey(4)
                        }
                    },
                    onBack = { sendKey(4) }
                )
                1 -> RemotePage(
                    deviceName = displayedDevice,
                    connected = connected,
                    connectionMethod = activeMethod,
                    apps = installedApps.filter { it.packageName in favoritePackages },
                    onKey = ::sendKey,
                    onApp = ::launchTvApp,
                    onAddApps = {
                        if (adb.isConnected()) showAppsSheet = true
                        else connectionStatus = "Список и запуск приложений доступны при ADB-подключении. MiLink управляет кнопками пульта."
                    }
                )
                else -> DevicesPage(
                    connected = connected,
                    deviceName = displayedDevice,
                    connectionMethod = activeMethod,
                    endpoints = endpoints,
                    selected = selectedEndpoint,
                    pendingMethod = pendingMethod,
                    scanning = scanning,
                    connecting = connecting,
                    status = connectionStatus,
                    onRefresh = { scanRequest += 1 },
                    onConnect = { showConnectSheet = true },
                    onManualIp = { showManualIpSheet = true },
                    onSelectEndpoint = { endpoint ->
                        selectedEndpoint = endpoint
                        when (pendingMethod) {
                            ConnectionMethod.BLUETOOTH -> connectionStatus =
                                "Bluetooth HID: открой Bluetooth-настройки телевизора и выбери Universal Remote Control."
                            null -> Unit
                            else -> connectEndpoint(endpoint, pendingMethod!!)
                        }
                    },
                    onForget = {
                        adb.disconnect()
                        hid.disconnect()
                        miLink.disconnect()
                        tvIdentity = null
                        tvDisplayName = ""
                        activeMethod = null
                        pendingMethod = null
                        connectionStatus = "Отключено"
                    }
                )
            }
        }

        if (showConnectSheet) {
            ConnectMethodSheet(
                onClose = { showConnectSheet = false },
                onSelect = ::chooseMethod
            )
        }
        if (showPairingSheet && pairTarget != null) {
            PairingCodeSheet(
                host = pairTarget!!.host,
                code = pairingCode,
                onCode = { pairingCode = it.filter(Char::isDigit).take(6) },
                onClose = { showPairingSheet = false },
                onPair = {
                    val target = pairTarget!!
                    val code = pairingCode
                    showPairingSheet = false
                    scope.launch {
                        connecting = true
                        connectionStatus = "Сопрягаю ADB…"
                        try {
                            adb.pairWireless(target.host, target.port, code)
                            connectionStatus = "Сопряжение готово. Ищу TLS-подключение…"
                            scanRequest += 1
                        } catch (error: Throwable) {
                            connectionStatus = error.message?.take(140) ?: "ADB-сопряжение не прошло"
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
                onValue = { manualIp = it.filter { char -> char.isDigit() || char == '.' }.take(15) },
                onClose = { showManualIpSheet = false },
                onConnect = {
                    val endpoint = AdbEndpoint("Ручной адрес", manualIp, 5555, AdbEndpointKind.TCP)
                    showManualIpSheet = false
                    val method = pendingMethod ?: ConnectionMethod.WIFI
                    connectEndpoint(endpoint, method)
                }
            )
        }
        if (showAppsSheet) {
            AppPickerSheet(
                apps = installedApps,
                selected = favoritePackages,
                onToggle = { packageName ->
                    persistFavorites(
                        if (packageName in favoritePackages) favoritePackages - packageName
                        else favoritePackages + packageName
                    )
                },
                onClose = { showAppsSheet = false }
            )
        }
    }
}

private fun beginBluetoothPairing(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    hid: BluetoothHidRemote,
    onStatus: (String) -> Unit
) {
    scope.launch {
        val ready = runCatching { hid.registerForTvPairing() }.getOrDefault(false)
        if (ready) {
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
        } else {
            onStatus("Телефон не смог включить Bluetooth HID. Проверь Bluetooth и разрешения.")
        }
    }
}

@Composable
private fun DevicesPage(
    connected: Boolean,
    deviceName: String,
    connectionMethod: ConnectionMethod?,
    endpoints: List<AdbEndpoint>,
    selected: AdbEndpoint?,
    pendingMethod: ConnectionMethod?,
    scanning: Boolean,
    connecting: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onManualIp: () -> Unit,
    onSelectEndpoint: (AdbEndpoint) -> Unit,
    onForget: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink)
            .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BrandMark()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected && connectionMethod != null) {
                    OutlineIcon(connectionGlyph(connectionMethod), 19.dp)
                }
                OutlineButton("Connect", onClick = onConnect, enabled = !connecting)
            }
        }
        Spacer(Modifier.height(22.dp))
        NavIndicators(active = 0, atTop = true, connectionMethod = connectionMethod)
        Spacer(Modifier.height(26.dp))

        if (connected) {
            Row(
                modifier = Modifier.fillMaxWidth().border(1.dp, Paper, RoundedCornerShape(15.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlineIcon(Glyph.TV, 28.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    UiText(deviceName, 15.sp, Paper, FontWeight.Medium)
                    UiText(connectionMethod?.title ?: "Подключено", 11.sp, Dim)
                }
                OutlineIcon(connectionMethod?.let(::connectionGlyph) ?: Glyph.TV, 20.dp)
                Spacer(Modifier.width(10.dp))
                OutlineButton("×", onClick = onForget, compact = true)
            }
            Spacer(Modifier.height(26.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            UiText("DEVICES", 11.sp, Dim, FontWeight.SemiBold, letterSpacing = 2.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlineIconButton(Glyph.IP, onManualIp, 30.dp)
                OutlineIconButton(Glyph.REFRESH, onRefresh, 30.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        UiText(status, 12.sp, Dim, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            endpoints.forEach { endpoint ->
                val isSelected = selected?.key == endpoint.key
                val title = when (endpoint.kind) {
                    AdbEndpointKind.TCP -> endpoint.name.ifBlank { "Android TV / ADB" }
                    AdbEndpointKind.TLS_CONNECT -> "Wireless debugging"
                    AdbEndpointKind.TLS_PAIRING -> "ADB pairing"
                    AdbEndpointKind.MI_LINK -> endpoint.model?.takeIf { it.isNotBlank() }
                        ?: endpoint.name.ifBlank { "Xiaomi TV (MiLink)" }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (isSelected) Paper else Hairline, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectEndpoint(endpoint) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlineIcon(Glyph.TV, 25.dp, if (isSelected) Paper else Dim)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        UiText(title, 14.sp, Paper)
                        UiText("${endpoint.host}:${endpoint.port}", 11.sp, Dim)
                    }
                    UiText(
                        when (endpoint.kind) {
                            AdbEndpointKind.TCP -> "ADB"
                            AdbEndpointKind.TLS_CONNECT -> "TLS"
                            AdbEndpointKind.TLS_PAIRING -> "PAIR"
                            AdbEndpointKind.MI_LINK -> "MILINK"
                        },
                        9.sp,
                        if (isSelected) Paper else Dim,
                        FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isSelected) OutlineIcon(Glyph.CHECK, 16.dp)
                }
            }
            if (endpoints.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    if (scanning) MorphingMark()
                    else OutlineIcon(Glyph.TV, 42.dp, Dim)
                    UiText(
                        if (scanning) "SEARCHING FOR TVS" else "NO DEVICES FOUND",
                        11.sp,
                        Dim,
                        letterSpacing = 2.sp
                    )
                    UiText(
                        "Для ADB включи отладку и разреши подключение на экране телевизора. Для Android 11+ можно использовать Wireless debugging.",
                        12.sp,
                        Dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.88f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (selected != null && pendingMethod != null &&
            pendingMethod != ConnectionMethod.BLUETOOTH && !connected
        ) {
            OutlineButton(
                if (connecting) "Connecting…" else "Connect to selected TV",
                onClick = { onSelectEndpoint(selected) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RemotePage(
    deviceName: String,
    connected: Boolean,
    connectionMethod: ConnectionMethod?,
    apps: List<TvApp>,
    onKey: (Int) -> Unit,
    onApp: (TvApp) -> Unit,
    onAddApps: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (connected && connectionMethod != null) {
                OutlineIcon(connectionGlyph(connectionMethod), 17.dp)
                Spacer(Modifier.width(8.dp))
            }
            UiText(
                if (connected) deviceName.uppercase(Locale.ROOT) else "UNIVERSAL REMOTE",
                10.sp,
                Dim,
                FontWeight.SemiBold,
                letterSpacing = 1.8.sp
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            DPad(onKey = onKey)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlineIconButton(Glyph.MENU, { onKey(82) }, 48.dp)
            OutlineIconButton(Glyph.BACK, { onKey(4) }, 48.dp)
            OutlineIconButton(Glyph.HOME, { onKey(3) }, 48.dp)
        }

        Spacer(Modifier.height(17.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            apps.take(5).forEach { app ->
                QuickAppButton(app, onClick = { onApp(app) })
                Spacer(Modifier.width(14.dp))
            }
            OutlineIconButton(Glyph.PLUS, onAddApps, 42.dp)
        }
        Spacer(Modifier.height(14.dp))
        NavIndicators(active = 1, atTop = false, connectionMethod = connectionMethod)
    }
}

@Composable
private fun DPad(onKey: (Int) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { point: Offset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = point.x - center.x
                    val dy = point.y - center.y
                    val radius = minOf(size.width, size.height).toFloat() * 0.245f
                    if (kotlin.math.sqrt(dx * dx + dy * dy) < radius * 0.82f) {
                        onKey(23)
                    } else if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        onKey(if (dx > 0) 22 else 21)
                    } else {
                        onKey(if (dy > 0) 20 else 19)
                    }
                })
            }
        ) {
            val side = size.minDimension
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = side * 0.245f
            val gap = side * 0.08f
            val extent = side * 0.17f
            val stroke = (side * 0.009f).coerceAtLeast(2.2.dp.toPx())
            drawCircle(Paper, radius, Offset(cx, cy), style = Stroke(stroke, cap = StrokeCap.Round))
            fun chevron(points: List<Offset>) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, Paper, style = Stroke(stroke * 1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val arm = side * 0.055f
            val top = cy - radius - gap
            val bottom = cy + radius + gap
            val left = cx - radius - gap
            val right = cx + radius + gap
            chevron(listOf(Offset(cx - arm, top + extent * 0.3f), Offset(cx, top - extent * 0.12f), Offset(cx + arm, top + extent * 0.3f)))
            chevron(listOf(Offset(cx - arm, bottom - extent * 0.3f), Offset(cx, bottom + extent * 0.12f), Offset(cx + arm, bottom - extent * 0.3f)))
            chevron(listOf(Offset(left + extent * 0.3f, cy - arm), Offset(left - extent * 0.12f, cy), Offset(left + extent * 0.3f, cy + arm)))
            chevron(listOf(Offset(right - extent * 0.3f, cy - arm), Offset(right + extent * 0.12f, cy), Offset(right - extent * 0.3f, cy + arm)))
        }
        UiText("OK", 25.sp, Paper, FontWeight.Light, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun MousePage(
    connected: Boolean,
    bluetooth: Boolean,
    connectionMethod: ConnectionMethod?,
    onBluetoothMove: (Float, Float) -> Unit,
    onBluetoothHoldStart: () -> Unit,
    onBluetoothHoldEnd: () -> Unit,
    onBluetoothTap: () -> Unit,
    onAdbTap: (Float, Float) -> Unit,
    onAdbSwipe: (Float, Float, Float, Float, Int) -> Unit,
    onRightClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink)
            .padding(horizontal = 23.dp, vertical = 21.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NavIndicators(active = 2, atTop = true, connectionMethod = connectionMethod)
            Spacer(Modifier.height(12.dp))
            UiText(
                if (bluetooth) "BLUETOOTH POINTER" else if (connected) "TOUCH CONTROL" else "MOUSE",
                10.sp,
                Dim,
                FontWeight.Medium,
                letterSpacing = 2.sp
            )
        }

        MouseTouchSurface(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 18.dp),
            bluetooth = bluetooth,
            onBluetoothMove = onBluetoothMove,
            onBluetoothHoldStart = onBluetoothHoldStart,
            onBluetoothHoldEnd = onBluetoothHoldEnd,
            onBluetoothTap = onBluetoothTap,
            onAdbTap = onAdbTap,
            onAdbSwipe = onAdbSwipe
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MouseActionButton(Glyph.LEFT_CLICK, "LEFT", {
                if (bluetooth) onBluetoothTap() else onAdbTap(0.5f, 0.5f)
            })
            MouseActionButton(Glyph.RIGHT_CLICK, "RIGHT", onRightClick)
            MouseActionButton(Glyph.BACK, "BACK", onBack)
        }
    }
}

@Composable
private fun MouseTouchSurface(
    modifier: Modifier,
    bluetooth: Boolean,
    onBluetoothMove: (Float, Float) -> Unit,
    onBluetoothHoldStart: () -> Unit,
    onBluetoothHoldEnd: () -> Unit,
    onBluetoothTap: () -> Unit,
    onAdbTap: (Float, Float) -> Unit,
    onAdbSwipe: (Float, Float, Float, Float, Int) -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(0.94f)
            .border(1.4.dp, Paper, RoundedCornerShape(26.dp))
            .pointerInput(bluetooth) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val start = down.position
                    var previous = start
                    var last = start
                    var moved = false
                    var held = false
                    var pressed = true

                    while (pressed) {
                        val event: PointerEvent = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - previous
                        if (change.pressed && delta.getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                        }
                        if (change.pressed && moved && delta.getDistance() > 0.5f) {
                            if (bluetooth) onBluetoothMove(delta.x, delta.y)
                            change.consume()
                        }
                        if (!held && !moved &&
                            change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                        ) {
                            held = true
                            if (bluetooth) onBluetoothHoldStart()
                        }
                        last = change.position
                        previous = change.position
                        pressed = change.pressed
                    }

                    val x1 = (start.x / width).coerceIn(0f, 1f)
                    val y1 = (start.y / height).coerceIn(0f, 1f)
                    val x2 = (last.x / width).coerceIn(0f, 1f)
                    val y2 = (last.y / height).coerceIn(0f, 1f)

                    if (bluetooth) {
                        if (held) onBluetoothHoldEnd()
                        else if (!moved) onBluetoothTap()
                    } else {
                        when {
                            held -> onAdbSwipe(x1, y1, x2, y2, 900)
                            moved -> onAdbSwipe(x1, y1, x2, y2, 380)
                            else -> onAdbTap(x1, y1)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        OutlineIcon(Glyph.POINTER, 36.dp, Dim)
    }
}

@Composable
private fun ConnectMethodSheet(
    onClose: () -> Unit,
    onSelect: (ConnectionMethod) -> Unit
) {
    OverlayFrame(title = "CONNECT", onClose = onClose) {
        ConnectionMethod.entries.forEach { method ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onSelect(method) }
                    .padding(vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlineIcon(connectionGlyph(method), 28.dp)
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    UiText(method.title, 15.sp, Paper, FontWeight.Medium)
                    UiText(method.hint, 11.sp, Dim)
                }
                OutlineIcon(Glyph.BACK, 16.dp)
            }
            if (method != ConnectionMethod.WIFI) ThinDivider()
        }
        Spacer(Modifier.height(8.dp))
        UiText(
            "Hotspot — это сеть. Само управление выполняется через ADB после того, как телевизор подключится к телефону.",
            11.sp,
            Dim
        )
    }
}

@Composable
private fun PairingCodeSheet(
    host: String,
    code: String,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    onPair: () -> Unit
) {
    OverlayFrame(title = "WIRELESS PAIRING", onClose = onClose) {
        UiText("Телевизор: $host", 12.sp, Dim)
        Spacer(Modifier.height(12.dp))
        UiText("На телевизоре открой Developer options → Wireless debugging → Pair device with pairing code.", 12.sp, Paper)
        Spacer(Modifier.height(20.dp))
        BasicTextField(
            value = code,
            onValueChange = onCode,
            modifier = Modifier.fillMaxWidth().border(1.dp, Paper, RoundedCornerShape(10.dp)).padding(15.dp),
            textStyle = TextStyle(color = Paper, fontSize = 22.sp, letterSpacing = 5.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Paper),
            singleLine = true,
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (code.isEmpty()) UiText("000000", 20.sp, Hairline, letterSpacing = 5.sp)
                    inner()
                }
            }
        )
        Spacer(Modifier.height(18.dp))
        OutlineButton("PAIR", onPair, enabled = code.length == 6, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ManualIpSheet(
    value: String,
    onValue: (String) -> Unit,
    onClose: () -> Unit,
    onConnect: () -> Unit
) {
    OverlayFrame(title = "MANUAL IP", onClose = onClose) {
        UiText("Для классического ADB по сети введи локальный IP телевизора. Порт используется 5555.", 12.sp, Dim)
        Spacer(Modifier.height(18.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().border(1.dp, Paper, RoundedCornerShape(10.dp)).padding(14.dp),
            textStyle = TextStyle(color = Paper, fontSize = 18.sp, letterSpacing = 1.sp),
            cursorBrush = SolidColor(Paper),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) UiText("192.168.1.100", 16.sp, Hairline)
                    inner()
                }
            }
        )
        Spacer(Modifier.height(18.dp))
        OutlineButton("CONNECT", onConnect, enabled = value.count { it == '.' } == 3, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AppPickerSheet(
    apps: List<TvApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit
) {
    OverlayFrame(title = "QUICK APPS", onClose = onClose) {
        UiText("Выбери приложения, которые будут показаны под кнопками пульта.", 12.sp, Dim)
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (app.packageName.contains("youtube", true)) OutlineIcon(Glyph.YOUTUBE, 22.dp)
                    else OutlineIcon(Glyph.TV, 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        UiText(app.displayName, 14.sp, Paper)
                        UiText(app.packageName, 9.sp, Dim)
                    }
                    if (app.packageName in selected) OutlineIcon(Glyph.CHECK, 18.dp)
                    else OutlineIcon(Glyph.PLUS, 18.dp, Dim)
                }
                ThinDivider()
            }
            if (apps.isEmpty()) {
                UiText("Не удалось получить список приложений. Проверь ADB-подключение.", 12.sp, Dim)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton("DONE", onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OverlayFrame(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Ink.copy(alpha = 0.96f)).padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().border(1.dp, Paper, RoundedCornerShape(20.dp))
                .background(Ink, RoundedCornerShape(20.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UiText(title, 11.sp, Dim, FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                OutlineIconButton(Glyph.CLOSE, onClose, 30.dp)
            }
            Spacer(Modifier.height(22.dp))
            content()
        }
    }
}

@Composable
private fun QuickAppButton(app: TvApp, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(48.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (app.packageName.contains("youtube", true)) OutlineIcon(Glyph.YOUTUBE, 23.dp)
        else OutlineIcon(Glyph.TV, 21.dp)
        UiText(app.displayName.take(9), 8.sp, Dim, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MouseActionButton(icon: Glyph, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(68.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        OutlineIcon(icon, 26.dp)
        UiText(label, 9.sp, Dim, letterSpacing = 1.sp)
    }
}

@Composable
private fun NavIndicators(active: Int, atTop: Boolean, connectionMethod: ConnectionMethod?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 58.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavIndicator(0, active, connectionMethod?.let(::connectionGlyph) ?: Glyph.TV)
        NavIndicator(1, active, Glyph.REMOTE)
        NavIndicator(2, active, Glyph.MOUSE)
    }
}

@Composable
private fun NavIndicator(index: Int, active: Int, glyph: Glyph) {
    if (index == active) {
        Box(Modifier.size(6.dp).background(Paper, CircleShape))
    } else {
        OutlineIcon(glyph, 18.dp, Paper)
    }
}

@Composable
private fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, if (enabled) Paper else Hairline, RoundedCornerShape(30.dp))
            .clip(RoundedCornerShape(30.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 5.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        UiText(text, if (compact) 13.sp else 10.sp, if (enabled) Paper else Dim, FontWeight.Medium, letterSpacing = 1.3.sp)
    }
}

@Composable
private fun OutlineIconButton(glyph: Glyph, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        OutlineIcon(glyph, size * 0.58f)
    }
}

@Composable
private fun OutlineIcon(glyph: Glyph, size: androidx.compose.ui.unit.Dp, color: Color = Paper) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w.coerceAtMost(h) * 0.075f).coerceAtLeast(1.5.dp.toPx())
        fun line(
            first: Offset,
            second: Offset,
            third: Offset? = null,
            fourth: Offset? = null,
            width: Float = stroke
        ) {
            val points = listOfNotNull(first, second, third, fourth)
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float = w * 0.12f) {
            drawRoundRect(
                color,
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        when (glyph) {
            Glyph.BLUETOOTH -> {
                line(Offset(w * .5f, h * .08f), Offset(w * .5f, h * .92f))
                line(Offset(w * .5f, h * .08f), Offset(w * .83f, h * .34f), Offset(w * .2f, h * .74f))
                line(Offset(w * .5f, h * .92f), Offset(w * .83f, h * .66f), Offset(w * .2f, h * .26f))
            }
            Glyph.PHONE -> {
                roundRect(w * .25f, h * .06f, w * .5f, h * .88f, w * .12f)
                drawCircle(color, stroke * .45f, Offset(w * .5f, h * .84f), style = Stroke(stroke * .65f))
            }
            Glyph.WIFI -> {
                drawArc(color, 220f, 100f, false, Offset(w * .08f, h * .16f), Size(w * .84f, h * .72f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(color, 225f, 90f, false, Offset(w * .25f, h * .34f), Size(w * .5f, h * .48f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color, stroke * .55f, Offset(w * .5f, h * .83f))
            }
            Glyph.REMOTE -> {
                roundRect(w * .31f, h * .06f, w * .38f, h * .88f, w * .16f)
                drawCircle(color, w * .045f, Offset(w * .5f, h * .23f), style = Stroke(stroke * .8f))
                line(Offset(w * .42f, h * .52f), Offset(w * .58f, h * .52f))
                line(Offset(w * .42f, h * .65f), Offset(w * .58f, h * .65f))
                line(Offset(w * .42f, h * .78f), Offset(w * .58f, h * .78f))
            }
            Glyph.TV -> {
                roundRect(w * .09f, h * .13f, w * .82f, h * .65f, w * .09f)
                line(Offset(w * .37f, h * .81f), Offset(w * .63f, h * .81f))
                line(Offset(w * .5f, h * .78f), Offset(w * .5f, h * .9f))
            }
            Glyph.MOUSE, Glyph.POINTER -> {
                line(Offset(w * .22f, h * .08f), Offset(w * .22f, h * .9f), Offset(w * .46f, h * .68f), Offset(w * .61f, h * .94f))
                line(Offset(w * .46f, h * .68f), Offset(w * .62f, h * .56f), Offset(w * .22f, h * .08f))
            }
            Glyph.HOME -> {
                line(Offset(w * .08f, h * .47f), Offset(w * .5f, h * .1f), Offset(w * .92f, h * .47f))
                line(Offset(w * .2f, h * .4f), Offset(w * .2f, h * .9f), Offset(w * .8f, h * .9f), Offset(w * .8f, h * .4f))
                line(Offset(w * .43f, h * .9f), Offset(w * .43f, h * .63f), Offset(w * .57f, h * .63f), Offset(w * .57f, h * .9f))
            }
            Glyph.MENU -> {
                repeat(3) { index ->
                    val y = h * (.23f + index * .27f)
                    drawCircle(color, stroke * 1.1f, Offset(w * .2f, y), style = Stroke(stroke * .72f))
                    line(Offset(w * .42f, y), Offset(w * .9f, y), width = stroke * .85f)
                }
            }
            Glyph.BACK -> {
                line(Offset(w * .83f, h * .18f), Offset(w * .36f, h * .5f), Offset(w * .83f, h * .82f))
                line(Offset(w * .38f, h * .5f), Offset(w * .9f, h * .5f))
            }
            Glyph.PLUS -> {
                line(Offset(w * .5f, h * .15f), Offset(w * .5f, h * .85f))
                line(Offset(w * .15f, h * .5f), Offset(w * .85f, h * .5f))
            }
            Glyph.YOUTUBE -> {
                roundRect(w * .07f, h * .2f, w * .86f, h * .6f, w * .16f)
                line(Offset(w * .43f, h * .35f), Offset(w * .66f, h * .5f), Offset(w * .43f, h * .65f), Offset(w * .43f, h * .35f))
            }
            Glyph.REFRESH -> {
                drawArc(color, 40f, 275f, false, Offset(w * .15f, h * .15f), Size(w * .7f, h * .7f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(w * .72f, h * .12f), Offset(w * .88f, h * .15f), Offset(w * .84f, h * .32f))
            }
            Glyph.KEYBOARD -> {
                roundRect(w * .08f, h * .2f, w * .84f, h * .6f, w * .08f)
                repeat(3) { row ->
                    repeat(4) { col ->
                        drawCircle(color, stroke * .32f, Offset(w * (.22f + col * .18f), h * (.37f + row * .13f)))
                    }
                }
            }
            Glyph.LEFT_CLICK, Glyph.RIGHT_CLICK -> {
                drawCircle(color, w * .35f, Offset(w * .5f, h * .5f), style = Stroke(stroke))
                line(Offset(w * .5f, h * .16f), Offset(w * .5f, h * .5f))
                if (glyph == Glyph.RIGHT_CLICK) line(Offset(w * .5f, h * .5f), Offset(w * .78f, h * .5f))
            }
            Glyph.CHECK -> {
                line(Offset(w * .15f, h * .52f), Offset(w * .4f, h * .78f), Offset(w * .87f, h * .2f))
            }
            Glyph.IP -> {
                roundRect(w * .12f, h * .12f, w * .76f, h * .76f, w * .1f)
                line(Offset(w * .28f, h * .35f), Offset(w * .72f, h * .35f))
                line(Offset(w * .28f, h * .52f), Offset(w * .72f, h * .52f))
                line(Offset(w * .28f, h * .69f), Offset(w * .58f, h * .69f))
            }
            Glyph.CLOSE -> {
                line(Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f))
                line(Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f))
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        UiText("UNIVERSAL", 20.sp, Paper, FontWeight.Light, letterSpacing = 4.sp)
        UiText("REMOTE", 8.sp, Dim, FontWeight.Medium, letterSpacing = 3.sp)
    }
}

@Composable
private fun MorphingMark() {
    Canvas(Modifier.size(40.dp)) {
        val side = size.minDimension
        val stroke = 2.6.dp.toPx()
        drawArc(Paper, 15f, 285f, false, Offset(2.dp.toPx(), 2.dp.toPx()), Size(side - 4.dp.toPx(), side - 4.dp.toPx()), style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(Paper, side * 0.09f, Offset(side * 0.78f, side * 0.18f))
    }
}

@Composable
private fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

private fun connectionGlyph(method: ConnectionMethod): Glyph = when (method) {
    ConnectionMethod.BLUETOOTH -> Glyph.BLUETOOTH
    ConnectionMethod.HOTSPOT -> Glyph.PHONE
    ConnectionMethod.WIFI -> Glyph.WIFI
}

@Composable
private fun UiText(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
            lineHeight = size * 1.35f
        )
    )
}
