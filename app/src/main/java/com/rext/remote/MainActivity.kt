package com.rext.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Page { DEVICES, REMOTE, MOUSE }
private enum class LinkMethod(val label: String) {
    BLUETOOTH("Bluetooth"),
    HOTSPOT("Phone hotspot"),
    WIFI("Shared Wi-Fi")
}

private val White = Color.White
private val Black = Color.Black
private val Muted = Color(0xFFBDBDBD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RemoteApp()
            }
        }
    }
}

@Composable
private fun RemoteApp() {
    var page by remember { mutableStateOf(Page.REMOTE) }
    var selectedTv by remember { mutableStateOf("Xiaomi Mi TV 4A") }
    var activeMethod by remember { mutableStateOf<LinkMethod?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .pointerInput(page) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        if (drag < -55f) page = when (page) {
                            Page.DEVICES -> Page.REMOTE
                            Page.REMOTE -> Page.MOUSE
                            Page.MOUSE -> Page.MOUSE
                        }
                        if (drag > 55f) page = when (page) {
                            Page.DEVICES -> Page.DEVICES
                            Page.REMOTE -> Page.DEVICES
                            Page.MOUSE -> Page.REMOTE
                        }
                        drag = 0f
                    }
                )
            }
    ) {
        when (page) {
            Page.REMOTE -> RemotePage(
                onDevices = { page = Page.DEVICES },
                onMouse = { page = Page.MOUSE }
            )
            Page.MOUSE -> MousePage(
                onDevices = { page = Page.DEVICES },
                onRemote = { page = Page.REMOTE }
            )
            Page.DEVICES -> DevicesPage(
                selectedTv = selectedTv,
                activeMethod = activeMethod,
                onSelect = { selectedTv = it },
                onConnect = { showConnect = true },
                onRemote = { page = Page.REMOTE },
                onMouse = { page = Page.MOUSE }
            )
        }

        if (showConnect) {
            AlertDialog(
                onDismissRequest = { showConnect = false },
                containerColor = Color(0xFF111111),
                titleContentColor = White,
                textContentColor = White,
                title = { Text("Connect to $selectedTv") },
                text = {
                    Column {
                        LinkMethod.entries.forEach { method ->
                            TextButton(
                                onClick = {
                                    activeMethod = method
                                    showConnect = false
                                    page = Page.DEVICES
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(method.label, color = White, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showConnect = false }) {
                        Text("Cancel", color = Muted)
                    }
                }
            )
        }
    }
}

@Composable
private fun RemotePage(onDevices: () -> Unit, onMouse: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(28.dp))
        DPad()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlineAction("MENU")
            OutlineAction("BACK")
            OutlineAction("HOME")
        }
        NavigationMarks(active = Page.REMOTE, onDevices = onDevices, onRemote = {}, onMouse = onMouse)
    }
}

@Composable
private fun DPad() {
    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * .235f
        val line = size.minDimension * .018f
        drawCircle(White, radius, Offset(cx, cy), style = Stroke(line))
        drawLine(White, Offset(cx, cy - radius - 62), Offset(cx, cy - radius - 10), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx, cy + radius + 10), Offset(cx, cy + radius + 62), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx - radius - 62, cy), Offset(cx - radius - 10, cy), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx + radius + 10, cy), Offset(cx + radius + 62, cy), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx - line * 3, cy - radius - 62), Offset(cx, cy - radius - 78), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx, cy - radius - 78), Offset(cx + line * 3, cy - radius - 62), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx - line * 3, cy + radius + 62), Offset(cx, cy + radius + 78), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx, cy + radius + 78), Offset(cx + line * 3, cy + radius + 62), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx - radius - 62, cy - line * 3), Offset(cx - radius - 78, cy), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx - radius - 78, cy), Offset(cx - radius - 62, cy + line * 3), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx + radius + 62, cy - line * 3), Offset(cx + radius + 78, cy), line * 3.1f, cap = StrokeCap.Round)
        drawLine(White, Offset(cx + radius + 78, cy), Offset(cx + radius + 62, cy + line * 3), line * 3.1f, cap = StrokeCap.Round)
    }
    Box(
        modifier = Modifier.size(1.dp),
        contentAlignment = Alignment.Center
    ) { }
}

@Composable
private fun OutlineAction(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(42.dp)) {
            val s = size.minDimension
            val stroke = s * .075f
            when (label) {
                "MENU" -> {
                    repeat(3) { i ->
                        val y = s * (.2f + i * .3f)
                        drawCircle(White, stroke * .48f, Offset(s * .18f, y))
                        drawLine(White, Offset(s * .38f, y), Offset(s * .88f, y), stroke, cap = StrokeCap.Round)
                    }
                }
                "BACK" -> {
                    drawLine(White, Offset(s*.78f,s*.18f), Offset(s*.35f,s*.5f), stroke, cap=StrokeCap.Round)
                    drawLine(White, Offset(s*.35f,s*.5f), Offset(s*.78f,s*.82f), stroke, cap=StrokeCap.Round)
                    drawArc(White, 270f, 180f, false, Offset(s*.25f,s*.28f), Size(s*.62f,s*.52f), style=Stroke(stroke, cap=StrokeCap.Round))
                }
                else -> {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(s*.1f,s*.48f); lineTo(s*.5f,s*.12f); lineTo(s*.9f,s*.48f)
                        lineTo(s*.82f,s*.48f); lineTo(s*.82f,s*.9f); lineTo(s*.58f,s*.9f)
                        lineTo(s*.58f,s*.65f); lineTo(s*.42f,s*.65f); lineTo(s*.42f,s*.9f)
                        lineTo(s*.18f,s*.9f); lineTo(s*.18f,s*.48f); close()
                    }
                    drawPath(path, White, style = Stroke(stroke, cap=StrokeCap.Round, join=androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun MousePage(onDevices: () -> Unit, onRemote: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        NavigationMarks(Page.MOUSE, onDevices, onRemote, {})
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = White,
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .08f),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
            Text(" ", color = White)
        }
        Text("Swipe left for remote", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DevicesPage(
    selectedTv: String,
    activeMethod: LinkMethod?,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onRemote: () -> Unit,
    onMouse: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Devices", color = White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = onConnect) { Text("Connect", color = White) }
        }
        Spacer(Modifier.height(28.dp))
        Text("Found devices", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(Icons.Outlined.Tv, null, tint = White)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(selectedTv, color = White, fontSize = 17.sp)
                Text(activeMethod?.label ?: "Ready to connect", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = onConnect) { Text("Connect", color = White) }
        }
        Spacer(Modifier.weight(1f))
        NavigationMarks(Page.DEVICES, {}, onRemote, onMouse)
    }
}

@Composable
private fun NavigationMarks(
    active: Page,
    onDevices: () -> Unit,
    onRemote: () -> Unit,
    onMouse: () -> Unit
) {
    val tint = White
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 58.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavMark(active == Page.DEVICES, Icons.Outlined.Bluetooth, onDevices)
        NavMark(active == Page.REMOTE, Icons.Outlined.Home, onRemote)
        NavMark(active == Page.MOUSE, Icons.Outlined.Mouse, onMouse)
    }
}

@Composable
private fun NavMark(active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    if (active) {
        Canvas(Modifier.size(7.dp)) { drawCircle(White) }
    } else {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = White,
            modifier = Modifier.size(19.dp)
        )
    }
}
