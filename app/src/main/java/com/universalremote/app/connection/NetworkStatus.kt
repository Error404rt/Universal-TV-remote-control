package com.universalremote.app.connection

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings

data class NetworkStatusSnapshot(
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val hotspotEnabled: Boolean = false,
    val localAddresses: List<LocalIpv4> = emptyList(),
    val wifiAddress: String? = null,
    val hotspotAddress: String? = null
) {
    val hasLocalAddress: Boolean get() = localAddresses.isNotEmpty()
}

/**
 * Best-effort view of the phone's local networks. Android does not expose a
 * public "hotspot is enabled" API, so the state combines the hidden Wi-Fi
 * access-point flag with interface names such as ap0/swlan0/wlan1.
 */
object NetworkStatus {
    fun snapshot(context: Context): NetworkStatusSnapshot {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = runCatching {
            connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull()

        val local = NetworkUtils.localInterfaces()
        val hotspotInterfaces = local.filter { NetworkUtils.isHotspotInterfaceName(it.interfaceName) }
        val wifiInterfaces = local.filterNot { NetworkUtils.isHotspotInterfaceName(it.interfaceName) }
        val apFlag = runCatching {
            val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as? Boolean ?: false
        }.getOrDefault(false)
        val wifiTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val activeInterface = runCatching {
            connectivity?.getLinkProperties(connectivity.activeNetwork)?.interfaceName
        }.getOrNull()

        // Several phones tether on wlan0 instead of ap0/swlan0 and do not expose
        // isWifiApEnabled. If a wlan interface has a local address while Wi-Fi is
        // not the active transport, that interface is the access point.
        val inferredHotspot = if (wifiTransport || hotspotInterfaces.isNotEmpty()) {
            null
        } else {
            local.firstOrNull { it.interfaceName.startsWith("wlan", ignoreCase = true) }
        }
        val hotspotAddress = hotspotInterfaces.firstOrNull()?.address ?: inferredHotspot?.address

        return NetworkStatusSnapshot(
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            wifiConnected = wifiTransport,
            hotspotEnabled = apFlag || hotspotInterfaces.isNotEmpty() || inferredHotspot != null,
            localAddresses = local,
            wifiAddress = if (wifiTransport) {
                (wifiInterfaces.firstOrNull { it.interfaceName == activeInterface }
                    ?: wifiInterfaces.firstOrNull { it.interfaceName.startsWith("wlan", ignoreCase = true) }
                    ?: wifiInterfaces.firstOrNull())?.address
            } else {
                null
            },
            hotspotAddress = hotspotAddress
        )
    }
}

/** Opens the system screens the user needs; a normal app cannot toggle a hotspot itself. */
object HotspotController {
    fun openSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        return launchFirst(context, intents)
    }

    fun openWifiSettings(context: Context): Boolean =
        launchFirst(context, listOf(Intent(Settings.ACTION_WIFI_SETTINGS)))

    fun openBluetoothSettings(context: Context): Boolean =
        launchFirst(context, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))

    private fun launchFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        }
        return false
    }
}
