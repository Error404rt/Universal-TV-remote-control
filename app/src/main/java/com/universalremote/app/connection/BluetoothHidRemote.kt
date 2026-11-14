package com.universalremote.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

enum class HidConnectionState { UNAVAILABLE, REGISTERING, WAITING_FOR_TV, CONNECTED, ERROR }

/**
 * Presents the phone as a Bluetooth HID keyboard + relative mouse.
 *
 * Pairing is initiated from the TV's Bluetooth accessory menu. The phone cannot force
 * an arbitrary TV to become a HID host or guarantee that every TV firmware supports it.
 */
@SuppressLint("MissingPermission")
class BluetoothHidRemote(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val mainExecutor: Executor = appContext.mainExecutor

    private val mutableState = MutableStateFlow(HidConnectionState.UNAVAILABLE)
    val stateFlow = mutableState.asStateFlow()
    val state: HidConnectionState get() = mutableState.value
    @Volatile var connectedHost: BluetoothDevice? = null
        private set

    // Set by disconnect() so callbacks triggered by unregistering the app do not
    // publish a stale ERROR state after the user intentionally disconnected.
    @Volatile private var closing = false

    private fun updateState(value: HidConnectionState) {
        if (closing && value != HidConnectionState.UNAVAILABLE) return
        mutableState.value = value
    }

    private var hid: BluetoothHidDevice? = null
    private var profileProxy: BluetoothProfile? = null
    private var registered = false
    private var registrationWaiter: CompletableDeferred<Boolean>? = null

    val isRegistered: Boolean get() = registered
    val isConnected: Boolean get() = connectedHost != null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (closing) return
            registered = isRegistered
            if (isRegistered) {
                updateState(if (connectedHost == null) HidConnectionState.WAITING_FOR_TV else HidConnectionState.CONNECTED)
                registrationWaiter?.complete(true)
            } else {
                connectedHost = null
                updateState(HidConnectionState.ERROR)
                registrationWaiter?.complete(false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            if (closing) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    updateState(HidConnectionState.CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device.address) connectedHost = null
                    updateState(if (registered) HidConnectionState.WAITING_FOR_TV else HidConnectionState.ERROR)
                }
                BluetoothProfile.STATE_CONNECTING -> updateState(HidConnectionState.REGISTERING)
                BluetoothProfile.STATE_DISCONNECTING -> Unit
            }
        }
    }

    suspend fun registerForTvPairing(): Boolean = withContext(Dispatchers.Main.immediate) {
        closing = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || adapter == null || !adapter.isEnabled) {
            updateState(HidConnectionState.UNAVAILABLE)
            return@withContext false
        }
        if (registered && hid != null) {
            updateState(if (connectedHost == null) HidConnectionState.WAITING_FOR_TV else HidConnectionState.CONNECTED)
            return@withContext true
        }

        updateState(HidConnectionState.REGISTERING)
        val waiter = CompletableDeferred<Boolean>()
        registrationWaiter = waiter
        val accepted = adapter.getProfileProxy(
            appContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (closing) {
                        adapter.closeProfileProxy(profile, proxy)
                        return
                    }
                    if (profile != BluetoothProfile.HID_DEVICE) {
                        adapter.closeProfileProxy(profile, proxy)
                        waiter.complete(false)
                        updateState(HidConnectionState.ERROR)
                        return
                    }
                    hid = proxy as BluetoothHidDevice
                    profileProxy = proxy
                    val settings = BluetoothHidDeviceAppSdpSettings(
                        "Universal Remote Control",
                        "TV remote control with mouse",
                        "Universal Remote Control",
                        BluetoothHidDevice.SUBCLASS1_COMBO,
                        HidReports.DESCRIPTOR
                    )
                    val result = runCatching {
                        hid?.registerApp(settings, null, null, mainExecutor, callback) == true
                    }.getOrDefault(false)
                    if (!result) {
                        updateState(HidConnectionState.ERROR)
                        waiter.complete(false)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (closing) return
                    registered = false
                    connectedHost = null
                    hid = null
                    profileProxy = null
                    updateState(HidConnectionState.ERROR)
                    waiter.complete(false)
                }
            },
            BluetoothProfile.HID_DEVICE
        )
        if (!accepted) {
            updateState(HidConnectionState.ERROR)
            return@withContext false
        }
        val result = runCatching {
            kotlinx.coroutines.withTimeout(12_000) { waiter.await() }
        }.getOrDefault(false)
        registrationWaiter = null
        if (!result) updateState(HidConnectionState.ERROR)
        result
    }

    suspend fun sendNavigationKey(key: NavigationKey): Boolean = withContext(Dispatchers.Main.immediate) {
        val device = connectedHost ?: return@withContext false
        val proxy = hid ?: return@withContext false
        val usage = key.keyboardUsage
        sendKeyboardUsageNow(proxy, device, usage)
    }

    suspend fun sendKeyboardUsage(usage: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        require(usage in 0..0xff)
        val device = connectedHost ?: return@withContext false
        val proxy = hid ?: return@withContext false
        sendKeyboardUsageNow(proxy, device, usage)
    }

    private fun sendKeyboardUsageNow(proxy: BluetoothHidDevice, device: BluetoothDevice, usage: Int): Boolean {
        val press = ByteArray(8).also { it[2] = usage.toByte() }
        val release = ByteArray(8)
        return runCatching {
            proxy.sendReport(device, HidReports.KEYBOARD_REPORT_ID, press) &&
                proxy.sendReport(device, HidReports.KEYBOARD_REPORT_ID, release)
        }.getOrDefault(false)
    }

    suspend fun sendConsumerUsage(usage: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        require(usage in 0..0x03ff)
        val device = connectedHost ?: return@withContext false
        val proxy = hid ?: return@withContext false
        val press = byteArrayOf((usage and 0xff).toByte(), ((usage shr 8) and 0xff).toByte())
        val release = byteArrayOf(0, 0)
        runCatching {
            proxy.sendReport(device, HidReports.CONSUMER_REPORT_ID, press) &&
                proxy.sendReport(device, HidReports.CONSUMER_REPORT_ID, release)
        }.getOrDefault(false)
    }

    suspend fun moveMouse(dx: Float, dy: Float, wheel: Float = 0f): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val device = connectedHost ?: return@withContext false
            val proxy = hid ?: return@withContext false
            val report = HidReports.mouse(dx, dy, wheel, buttons = 0)
            runCatching { proxy.sendReport(device, HidReports.MOUSE_REPORT_ID, report) }.getOrDefault(false)
        }

    suspend fun mouseButton(button: MouseButton, pressed: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val device = connectedHost ?: return@withContext false
            val proxy = hid ?: return@withContext false
            val report = HidReports.mouse(0f, 0f, 0f, buttons = if (pressed) button.mask else 0)
            runCatching { proxy.sendReport(device, HidReports.MOUSE_REPORT_ID, report) }.getOrDefault(false)
        }

    suspend fun click(button: MouseButton = MouseButton.LEFT): Boolean {
        val down = mouseButton(button, true)
        val up = mouseButton(button, false)
        return down && up
    }

    fun disconnect() {
        closing = true
        runCatching {
            connectedHost?.let { hid?.disconnect(it) }
            hid?.unregisterApp()
            profileProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        }
        connectedHost = null
        registered = false
        hid = null
        profileProxy = null
        registrationWaiter?.complete(false)
        registrationWaiter = null
        mutableState.value = HidConnectionState.UNAVAILABLE
    }

    companion object {
        fun hasHidDeviceProfile(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.getSystemService(BluetoothManager::class.java)?.adapter != null
    }
}

enum class NavigationKey(val keyboardUsage: Int) {
    UP(0x52),
    DOWN(0x51),
    LEFT(0x50),
    RIGHT(0x4F),
    OK(0x28),
    BACK(0x29)
}

enum class MouseButton(val mask: Int) {
    LEFT(0x01),
    RIGHT(0x02),
    MIDDLE(0x04)
}

private object HidReports {
    const val KEYBOARD_REPORT_ID = 1
    const val MOUSE_REPORT_ID = 2
    const val CONSUMER_REPORT_ID = 3

    // Report 1: keyboard. Report 2: relative mouse. Report 3: consumer-control usages.
    val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
        0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte(),
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte(),
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
        0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
        0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00, 0xC0.toByte()
    )

    fun mouse(dx: Float, dy: Float, wheel: Float, buttons: Int): ByteArray {
        fun axis(value: Float) = value.coerceIn(-127f, 127f).toInt().toByte()
        return byteArrayOf(buttons.toByte(), axis(dx), axis(dy), axis(wheel))
    }
}
