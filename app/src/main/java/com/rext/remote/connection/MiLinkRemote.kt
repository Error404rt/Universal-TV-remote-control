package com.rext.remote.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Xiaomi MiLink remote transport (TCP 6091).
 * It sends Android key events to the TV. It does not expose a standard system cursor or app list.
 */
class MiLinkRemote : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val packetIds = AtomicInteger(1)

    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null

    private val mutableState = MutableStateFlow(false)
    val stateFlow = mutableState.asStateFlow()

    suspend fun connect(host: String, port: Int = MILINK_PORT) = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Разрешено подключение только к телевизору в локальной сети" }
        require(port == MILINK_PORT) { "Неподдерживаемый порт MiLink" }
        disconnect()

        val candidate = Socket()
        candidate.tcpNoDelay = true
        candidate.keepAlive = true
        candidate.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        synchronized(lock) {
            socket = candidate
            output = DataOutputStream(candidate.getOutputStream())
            mutableState.value = true
        }

        readerJob = scope.launch {
            try {
                val input = DataInputStream(candidate.getInputStream())
                while (isActive && !candidate.isClosed) {
                    val type = input.read()
                    if (type < 0) break
                    val length = input.readUnsignedShort()
                    require(length <= MAX_FRAME_SIZE)
                    val packet = ByteArray(length)
                    input.readFully(packet)
                }
            } catch (_: Exception) {
                // Socket state is cleared by closeSocket below.
            } finally {
                closeSocket(candidate)
            }
        }

        heartbeatJob = scope.launch {
            while (isActive && candidate.isConnected && !candidate.isClosed) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    writeFrame(candidate, HEARTBEAT_FRAME)
                } catch (_: Exception) {
                    closeSocket(candidate)
                    break
                }
            }
        }
    }

    suspend fun sendKey(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        require(androidKeyCode in 0..300)
        val active = socket ?: error("Сначала подключись к Xiaomi TV")
        writeFrame(active, keyFrame(packetIds.getAndIncrement(), androidKeyCode, ACTION_DOWN))
        delay(KEY_RELEASE_DELAY_MS)
        writeFrame(active, keyFrame(packetIds.getAndIncrement(), androidKeyCode, ACTION_UP))
    }

    fun isConnected(): Boolean {
        val active = socket ?: return false
        return mutableState.value && active.isConnected && !active.isClosed
    }

    fun disconnect() {
        val old = synchronized(lock) {
            val current = socket
            socket = null
            output = null
            mutableState.value = false
            current
        }
        heartbeatJob?.cancel()
        readerJob?.cancel()
        if (old != null) runCatching { old.close() }
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun writeFrame(active: Socket, bytes: ByteArray) {
        synchronized(lock) {
            check(socket === active && !active.isClosed) { "Соединение с телевизором потеряно" }
            val stream = output ?: error("MiLink output stream is closed")
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun closeSocket(expected: Socket) {
        val old = synchronized(lock) {
            if (socket !== expected) return
            socket = null
            output = null
            mutableState.value = false
            expected
        }
        heartbeatJob?.cancel()
        runCatching { old.close() }
    }

    private fun keyFrame(id: Int, keyCode: Int, action: Int): ByteArray {
        val keyData = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { data ->
                data.writeByte(1); data.writeInt(0)       // metaState
                data.writeByte(2); data.writeInt(action)  // DOWN / UP
                data.writeByte(3); data.writeInt(keyCode) // Android key code
                data.writeByte(4); data.writeInt(0)       // scanCode
                data.writeByte(5); data.writeInt(0)       // repeatCount
                data.writeByte(6); data.writeInt(0)       // flags
                data.writeByte(7); data.writeLong(0L)     // TV stamps downTime
                data.writeByte(8); data.writeLong(0L)     // TV stamps eventTime
                data.writeByte(10); data.writeInt(-1)     // virtual keyboard device id
                data.writeByte(11); data.writeInt(0x101)  // SOURCE_KEYBOARD
            }
        }.toByteArray()
        val body = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { data ->
                data.writeByte(1)
                data.writeInt(id)
                data.writeShort(keyData.size)
                data.write(keyData)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { data ->
                data.writeByte(FRAME_KEY)
                data.writeShort(body.size)
                data.write(body)
            }
        }.toByteArray()
    }

    private fun isPrivateIpv4(value: String): Boolean = runCatching {
        val address = InetAddress.getByName(value)
        if (address !is Inet4Address) return@runCatching false
        val octets = address.address.map { it.toInt() and 0xff }
        octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }.getOrDefault(false)

    private companion object {
        const val MILINK_PORT = 6091
        const val CONNECT_TIMEOUT_MS = 2500
        const val HEARTBEAT_INTERVAL_MS = 5000L
        const val KEY_RELEASE_DELAY_MS = 25L
        const val MAX_FRAME_SIZE = 16384
        const val FRAME_KEY = 4
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        val HEARTBEAT_FRAME = byteArrayOf(2, 0, 0)
    }
}
