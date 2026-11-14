package com.universalremote.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalremote.app.connection.ConnectionMethod
import com.universalremote.app.connection.NetworkStatusSnapshot
import com.universalremote.app.connection.TvApp
import com.universalremote.app.connection.TvCandidate
import com.universalremote.app.connection.TvProtocol
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
internal fun DevicesPage(
    connected: Boolean,
    deviceName: String,
    connectionMethod: ConnectionMethod?,
    candidates: List<TvCandidate>,
    selected: TvCandidate?,
    scanning: Boolean,
    connecting: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onManualIp: () -> Unit,
    onSelect: (TvCandidate) -> Unit,
    onRemoveSaved: (TvCandidate) -> Unit,
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
            OutlineButton("Connect", onClick = onConnect, enabled = !connecting)
        }
        Spacer(Modifier.height(24.dp))

        if (connected) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, Paper, RoundedCornerShape(15.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlineIcon(Glyph.TV, 28.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    UiText(deviceName, 15.sp, Paper, FontWeight.Medium)
                    UiText(connectionMethod?.title ?: "Подключено", 11.sp, Dim)
                }
                if (connectionMethod != null) {
                    OutlineIcon(connectionGlyph(connectionMethod), 20.dp)
                    Spacer(Modifier.width(10.dp))
                }
                OutlineButton("×", onClick = onForget, compact = true)
            }
            Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(10.dp))
        UiText(status, 11.sp, Dim, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            candidates.forEach { candidate ->
                val isSelected = selected?.key == candidate.key
                val protocolLabel = candidate.protocols.joinToString(" · ") { it.shortTitle }
                    .ifBlank { "TV" }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (isSelected) Paper else Hairline, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(candidate) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlineIcon(
                        if (candidate.isBluetooth) Glyph.BLUETOOTH else Glyph.TV,
                        25.dp,
                        if (isSelected) Paper else Dim
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        UiText(candidate.name, 14.sp, Paper)
                        Spacer(Modifier.height(2.dp))
                        UiText(candidate.subtitle(), 10.sp, Dim)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        UiText(
                            candidate.statusLabel(),
                            8.sp,
                            if (isSelected) Paper else Dim,
                            FontWeight.SemiBold,
                            letterSpacing = 1.1.sp
                        )
                        if (candidate.saved == null) {
                            UiText(protocolLabel, 8.sp, Dim, letterSpacing = .6.sp)
                        }
                    }
                    if (candidate.saved != null) {
                        Spacer(Modifier.width(6.dp))
                        OutlineIconButton(
                            Glyph.CLOSE,
                            { onRemoveSaved(candidate) },
                            28.dp
                        )
                    } else if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        OutlineIcon(Glyph.CHECK, 16.dp)
                    }
                }
            }

            if (candidates.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    if (scanning) MorphingMark() else OutlineIcon(Glyph.TV, 42.dp, Dim)
                    UiText(
                        if (scanning) "SEARCHING FOR TVS" else "NO DEVICES FOUND",
                        11.sp,
                        Dim,
                        letterSpacing = 2.sp
                    )
                    UiText(
                        "Android TV Remote находится автоматически. ADB по сети требует включённой отладки на телевизоре. " +
                            "Сохранённые телевизоры остаются в списке, даже когда они выключены.",
                        12.sp,
                        Dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        NavIndicators(active = 2, connectionMethod = connectionMethod)
    }
}

@Composable
internal fun RemotePage(
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
                if (connected) deviceName.uppercase() else "UNIVERSAL REMOTE CONTROL",
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
            apps.take(3).forEach { app ->
                QuickAppButton(app, onClick = { onApp(app) })
                Spacer(Modifier.width(14.dp))
            }
            OutlineIconButton(Glyph.PLUS, onAddApps, 42.dp)
        }
        Spacer(Modifier.height(14.dp))
        NavIndicators(active = 1, connectionMethod = connectionMethod)
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
                    if (sqrt(dx * dx + dy * dy) < radius * 0.82f) {
                        onKey(23)
                    } else if (abs(dx) > abs(dy)) {
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
internal fun MousePage(
    connected: Boolean,
    bluetooth: Boolean,
    connectionMethod: ConnectionMethod?,
    hint: String?,
    onBluetoothMove: (Float, Float) -> Unit,
    onBluetoothHoldStart: () -> Unit,
    onBluetoothHoldEnd: () -> Unit,
    onBluetoothTap: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Int) -> Unit,
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
            Spacer(Modifier.height(12.dp))
            UiText(
                if (bluetooth) "BLUETOOTH POINTER" else if (connected) "TOUCH CONTROL" else "MOUSE",
                10.sp,
                Dim,
                FontWeight.Medium,
                letterSpacing = 2.sp
            )
            if (hint != null) {
                Spacer(Modifier.height(7.dp))
                UiText(hint, 10.sp, Hairline, textAlign = TextAlign.Center)
            }
        }

        MouseTouchSurface(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 18.dp),
            bluetooth = bluetooth,
            onBluetoothMove = onBluetoothMove,
            onBluetoothHoldStart = onBluetoothHoldStart,
            onBluetoothHoldEnd = onBluetoothHoldEnd,
            onBluetoothTap = onBluetoothTap,
            onTap = onTap,
            onLongPress = onLongPress,
            onSwipe = onSwipe
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MouseActionButton(Glyph.LEFT_CLICK, "LEFT", {
                if (bluetooth) onBluetoothTap() else onTap(0.5f, 0.5f)
            })
            MouseActionButton(Glyph.RIGHT_CLICK, "RIGHT", onRightClick)
            MouseActionButton(Glyph.BACK, "BACK", onBack)
        }
        NavIndicators(active = 0, connectionMethod = connectionMethod)
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
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Int) -> Unit
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
                            moved -> onSwipe(x1, y1, x2, y2, if (held) 900 else 380)
                            held -> onLongPress(x1, y1)
                            else -> onTap(x1, y1)
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
internal fun ConnectMethodSheet(
    network: NetworkStatusSnapshot,
    onClose: () -> Unit,
    onSelect: (ConnectionMethod) -> Unit
) {
    OverlayFrame(title = "CONNECT", onClose = onClose) {
        ConnectionMethod.entries.forEach { method ->
            val hint = when (method) {
                ConnectionMethod.BLUETOOTH -> method.hint
                ConnectionMethod.HOTSPOT -> if (network.hotspotEnabled) {
                    "Точка доступа включена · ${network.hotspotAddress ?: "адрес не определён"}"
                } else {
                    "Включи точку доступа телефона. Телевизор должен быть подключён к ней."
                }
                ConnectionMethod.WIFI -> if (network.wifiConnected) {
                    "Wi-Fi подключён · ${network.wifiAddress ?: "адрес не определён"}"
                } else {
                    "Включи Wi-Fi телефона и подключи телевизор к той же сети."
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onSelect(method) }
                    .padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlineIcon(connectionGlyph(method), 28.dp)
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    UiText(method.title, 15.sp, Paper, FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    UiText(hint, 11.sp, Dim)
                }
                OutlineIcon(Glyph.BACK, 16.dp)
            }
            if (method != ConnectionMethod.WIFI) ThinDivider()
        }
        Spacer(Modifier.height(14.dp))
        UiText(
            "Android TV Remote не требует USB-отладки. Отладка нужна только для ADB: списка приложений, " +
                "запуска по package ID и сенсорных координат. Точка доступа не может быть включена приложением " +
                "автоматически — откроются системные настройки.",
            11.sp,
            Dim
        )
    }
}

@Composable
internal fun PairingCodeSheet(
    host: String,
    code: String,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    onPair: () -> Unit
) {
    OverlayFrame(title = "ANDROID TV PAIRING", onClose = onClose) {
        UiText("Телевизор: $host", 12.sp, Dim)
        Spacer(Modifier.height(12.dp))
        UiText(
            "Введи шестисимвольный код, который показан в системном окне сопряжения Android TV Remote на телевизоре. " +
                "Если окно не появилось, подтверди подключение на телевизоре и попробуй снова.",
            12.sp,
            Paper
        )
        Spacer(Modifier.height(20.dp))
        BasicTextField(
            value = code,
            onValueChange = onCode,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, Paper, RoundedCornerShape(10.dp))
                .padding(15.dp),
            textStyle = TextStyle(
                color = Paper,
                fontSize = 22.sp,
                letterSpacing = 5.sp,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(Paper),
            singleLine = true,
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (code.isEmpty()) {
                        UiText("A1B2C3", 20.sp, Hairline, letterSpacing = 5.sp)
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(18.dp))
        OutlineButton("PAIR", onPair, enabled = code.length == 6, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ManualIpSheet(
    value: String,
    protocol: TvProtocol,
    onValue: (String) -> Unit,
    onProtocol: (TvProtocol) -> Unit,
    onClose: () -> Unit,
    onConnect: () -> Unit
) {
    OverlayFrame(title = "MANUAL IP", onClose = onClose) {
        UiText(
            "Введи локальный IPv4-адрес телевизора. Выбери Android TV Remote (порт 6466) или ADB (порт 5555).",
            12.sp,
            Dim
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(TvProtocol.ANDROID_TV_REMOTE, TvProtocol.ADB).forEach { option ->
                val active = protocol == option
                Box(
                    modifier = Modifier
                        .border(1.dp, if (active) Paper else Hairline, RoundedCornerShape(30.dp))
                        .clip(RoundedCornerShape(30.dp))
                        .clickable { onProtocol(option) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    UiText(
                        option.shortTitle,
                        9.sp,
                        if (active) Paper else Dim,
                        FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, Paper, RoundedCornerShape(10.dp))
                .padding(14.dp),
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
        Spacer(Modifier.height(9.dp))
        UiText("Порт: ${protocol.defaultPort}", 10.sp, Dim)
        Spacer(Modifier.height(18.dp))
        OutlineButton(
            "CONNECT",
            onConnect,
            enabled = value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun AppPickerSheet(
    apps: List<TvApp>,
    selected: List<String>,
    note: String,
    onToggle: (String) -> Unit,
    onClose: () -> Unit
) {
    OverlayFrame(title = "QUICK APPS", onClose = onClose) {
        UiText(note, 11.sp, Dim)
        Spacer(Modifier.height(8.dp))
        UiText("Выбрано ${selected.size} из 3", 10.sp, Hairline, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onToggle(app.packageName) }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlineIcon(
                        if (app.packageName.contains("youtube", true)) Glyph.YOUTUBE else Glyph.TV,
                        22.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        UiText(app.displayName, 14.sp, Paper)
                        UiText(app.packageName, 9.sp, Dim)
                    }
                    when {
                        app.packageName in selected -> OutlineIcon(Glyph.CHECK, 18.dp)
                        selected.size >= 3 -> OutlineIcon(Glyph.PLUS, 18.dp, Hairline)
                        else -> OutlineIcon(Glyph.PLUS, 18.dp, Dim)
                    }
                }
                ThinDivider()
            }
            if (apps.isEmpty()) {
                UiText("Список приложений пуст. Проверь подключение телевизора.", 12.sp, Dim)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton("DONE", onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun OnboardingSheet(onClose: () -> Unit) {
    OverlayFrame(title = "ПЕРЕД ПОДКЛЮЧЕНИЕМ", onClose = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            UiText("1 · Одна сеть", 12.sp, Paper, FontWeight.SemiBold)
            UiText(
                "Телефон и телевизор должны быть в одной сети: общий Wi-Fi или точка доступа телефона. " +
                    "Для точки доступа телевизор должен хотя бы один раз подключиться к ней и сохранить сеть.",
                11.sp,
                Dim
            )
            UiText("2 · Android TV Remote", 12.sp, Paper, FontWeight.SemiBold)
            UiText(
                "Основной способ. При первом подключении телевизор покажет шестисимвольный код — введи его здесь. " +
                    "Сопряжение не требует USB-отладки и работает на Xiaomi Mi TV 4A 32.",
                11.sp,
                Dim
            )
            UiText("3 · USB-отладка — необязательно", 12.sp, Paper, FontWeight.SemiBold)
            UiText(
                "Для пульта отладка не нужна. Она требуется только для ADB: полного списка установленных приложений, " +
                    "запуска по package ID и сенсорных координат. Если нужен ADB: на телевизоре открой Настройки → " +
                    "О телевизоре → 7 раз нажми «Сборка», затем включи «Отладка по USB» и, если доступна, «Сетевая отладка».",
                11.sp,
                Dim
            )
            UiText("4 · Bluetooth", 12.sp, Paper, FontWeight.SemiBold)
            UiText(
                "Режим Bluetooth HID не требует сети. Сопряжение начинается на телевизоре: " +
                    "Bluetooth → Добавить устройство → Universal Remote Control.",
                11.sp,
                Dim
            )
        }
        Spacer(Modifier.height(18.dp))
        OutlineButton("ПОНЯТНО", onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun OverlayFrame(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Ink.copy(alpha = 0.96f))
            // Hit-test target for the whole sheet layer. Its presence keeps the
            // pager and hidden controls behind the sheet from receiving input,
            // while children still get their normal events.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pressed = true
                    while (pressed) {
                        pressed = awaitPointerEvent().changes.any { it.pressed }
                    }
                }
            }
            .imePadding()
            .padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, Paper, RoundedCornerShape(20.dp))
                .background(Ink, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UiText(
                    title,
                    11.sp,
                    Dim,
                    FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
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
        OutlineIcon(
            if (app.packageName.contains("youtube", true)) Glyph.YOUTUBE else Glyph.TV,
            if (app.packageName.contains("youtube", true)) 23.dp else 21.dp
        )
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
private fun NavIndicators(active: Int, connectionMethod: ConnectionMethod?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
            if (connectionMethod != null) {
                OutlineIcon(connectionGlyph(connectionMethod), 18.dp)
            } else {
                OutlineIcon(Glyph.TV, 18.dp, Hairline)
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavIndicator(0, active, Glyph.MOUSE)
            NavIndicator(1, active, Glyph.REMOTE)
            NavIndicator(2, active, Glyph.TV)
        }
        Spacer(Modifier.width(40.dp))
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
