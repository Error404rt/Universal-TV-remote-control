package com.universalremote.app.connection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small persistent list of TVs the user has connected to. It only stores network
 * identity and the selected connection method; ADB/TLS secrets live elsewhere.
 */
class SavedTvStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("universal_remote_saved_tvs", Context.MODE_PRIVATE)

    fun load(): List<SavedTv> = decode(preferences.getString(KEY_DEVICES, null))

    fun upsert(tv: SavedTv): List<SavedTv> {
        val current = load().filterNot { saved ->
            saved.id == tv.id ||
                (tv.protocol != TvProtocol.BLUETOOTH_HID && saved.host == tv.host)
        }
        val next = (listOf(tv.copy(updatedAt = System.currentTimeMillis())) + current)
            .sortedByDescending { it.updatedAt }
            .take(MAX_SAVED_TVS)
        save(next)
        return next
    }

    fun remove(id: String): List<SavedTv> {
        val next = load().filterNot { it.id == id }
        save(next)
        return next
    }

    fun find(id: String): SavedTv? = load().firstOrNull { it.id == id }

    fun findByHost(host: String): SavedTv? = load().firstOrNull { it.host == host }

    fun clear(): List<SavedTv> {
        save(emptyList())
        return emptyList()
    }

    private fun save(devices: List<SavedTv>) {
        preferences.edit().putString(KEY_DEVICES, encode(devices)).apply()
    }

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val MAX_SAVED_TVS = 16

        fun encode(devices: List<SavedTv>): String {
            val array = JSONArray()
            devices.forEach { tv ->
                array.put(JSONObject().apply {
                    put("id", tv.id)
                    put("name", tv.name)
                    put("host", tv.host)
                    put("port", tv.port)
                    put("protocol", tv.protocol.name)
                    put("method", tv.method.name)
                    put("updatedAt", tv.updatedAt)
                })
            }
            return array.toString()
        }

        fun decode(value: String?): List<SavedTv> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val protocol = TvProtocol.fromName(item.optString("protocol")) ?: continue
                        val method = ConnectionMethod.entries
                            .firstOrNull { it.name == item.optString("method") }
                            ?: ConnectionMethod.WIFI
                        val host = item.optString("host").trim()
                        if (!isValidHost(protocol, host)) continue
                        val port = item.optInt("port", protocol.defaultPort)
                        if (protocol == TvProtocol.BLUETOOTH_HID) {
                            if (port != 0) continue
                        } else if (port !in 1..65535) {
                            continue
                        }
                        val name = item.optString("name").takeIf { it.isNotBlank() }
                            ?: if (protocol == TvProtocol.BLUETOOTH_HID) "Bluetooth TV" else "TV $host"
                        add(
                            SavedTv(
                                id = item.optString("id")
                                    .takeIf { it.isNotBlank() }
                                    ?: SavedTv.idFor(protocol, host),
                                name = name.take(96),
                                host = host,
                                port = port,
                                protocol = protocol,
                                method = method,
                                updatedAt = item.optLong("updatedAt", 0L)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        private fun isValidHost(protocol: TvProtocol, host: String): Boolean = when (protocol) {
            TvProtocol.BLUETOOTH_HID -> host == SavedTv.BLUETOOTH_HOST
            else -> NetworkUtils.isPrivateIpv4(host)
        }
    }
}
