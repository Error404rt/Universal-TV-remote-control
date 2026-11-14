package com.rext.remote.connection

import android.content.Context
import android.util.Base64
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

enum class AndroidTvRemotePhase { DISCONNECTED, CONNECTING, AWAITING_CODE, CONNECTED, ERROR }

data class AndroidTvRemoteState(
    val phase: AndroidTvRemotePhase = AndroidTvRemotePhase.DISCONNECTED,
    val host: String? = null,
    val message: String = ""
)

/**
 * Android TV Remote v2 local-network provider.
 *
 * The pairing exchange and protobuf field layout follow the MIT-licensed OpenTV-Remote
 * interoperability implementation. See docs/THIRD_PARTY_NOTICES.md.
 */
class AndroidTvRemoteV2(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("android_tv_remote_v2", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var pairingSocket: SSLSocket? = null
    @Volatile private var remoteSocket: SSLSocket? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var activeHost: String? = null
    @Volatile private var featureMask: Int = 0

    private val mutableState = MutableStateFlow(AndroidTvRemoteState())
    val stateFlow = mutableState.asStateFlow()

    init {
        AdbIdentity.configure(appContext)
    }

    suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Разрешено подключение только к телевизору в локальной сети" }
        disconnect()
        activeHost = host
        mutableState.value = AndroidTvRemoteState(AndroidTvRemotePhase.CONNECTING, host, "Подключаюсь к Android TV Remote…")

        try {
            if (preferences.getString(pinKey(host), null) == null) {
                beginPairing(host)
            } else {
                startRemote(host)
            }
        } catch (error: Throwable) {
            closeSockets()
            mutableState.value = AndroidTvRemoteState(
                AndroidTvRemotePhase.ERROR,
                host,
                error.message?.take(180) ?: "Не удалось подключиться к Android TV Remote"
            )
            throw error
        }
    }

    suspend fun pair(code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[0-9a-fA-F]{6}"))) {
            "Код сопряжения должен содержать шесть символов от 0 до 9 или A до F"
        }
        val host = activeHost ?: error("Сначала выбери телевизор")
        val socket = pairingSocket ?: error("Время сопряжения истекло. Подключись ещё раз.")
        try {
            val identity = clientIdentity()
            val clientKey = identity.first.publicKey as? RSAPublicKey
                ?: error("Ключ клиента не является RSA")
            val serverCertificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("Телевизор не предоставил сертификат")
            val serverKey = serverCertificate.publicKey as? RSAPublicKey
                ?: error("Телевизор использует неподдерживаемый ключ")

            val codeTail = byteArrayOf(
                code.substring(2, 4).toInt(16).toByte(),
                code.substring(4, 6).toInt(16).toByte()
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(
                unsigned(clientKey.modulus) +
                    unsigned(clientKey.publicExponent) +
                    unsigned(serverKey.modulus) +
                    unsigned(serverKey.publicExponent) +
                    codeTail
            )
            val expectedPrefix = code.substring(0, 2).toInt(16)
            require((digest[0].toInt() and 0xff) == expectedPrefix) {
                "Код не совпадает с телевизором. Проверь код на экране ТВ."
            }

            exchange(socket, 40, Proto.bytes(1, digest), 41)
            preferences.edit().putString(pinKey(host), fingerprint(serverCertificate)).commit()
            pairingSocket = null
            runCatching { socket.close() }
            startRemote(host)
        } catch (error: Throwable) {
            pairingSocket = null
            runCatching { socket.close() }
            mutableState.value = AndroidTvRemoteState(
                AndroidTvRemotePhase.ERROR,
                host,
                error.message?.take(180) ?: "Сопряжение не удалось"
            )
            throw error
        }
    }

    suspend fun sendKey(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        require(androidKeyCode in 0..300) { "Некорректная команда" }
        check(isConnected()) { "Сначала подключись к телевизору" }
        send(Proto.bytes(10, Proto.message(
            Proto.int(1, androidKeyCode),
            Proto.int(2, KEY_SHORT)
        )))
    }

    fun isConnected(): Boolean {
        val socket = remoteSocket ?: return false
        return mutableState.value.phase == AndroidTvRemotePhase.CONNECTED &&
            socket.isConnected && !socket.isClosed
    }

    fun forget() {
        activeHost?.let { preferences.edit().remove(pinKey(it)).commit() }
        disconnect()
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        val pair = pairingSocket
        val remote = remoteSocket
        pairingSocket = null
        remoteSocket = null
        activeHost = null
        featureMask = 0
        mutableState.value = AndroidTvRemoteState()
        runCatching { pair?.close() }
        runCatching { remote?.close() }
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun beginPairing(host: String) {
        val socket = tlsSocket(host, PAIRING_PORT, pairing = true)
        pairingSocket = socket

        exchange(
            socket,
            10,
            Proto.message(Proto.string(1, "atvremote"), Proto.string(2, "Universal Remote Control")),
            11
        )
        val encoding = Proto.message(Proto.int(1, 3), Proto.int(2, 6))
        exchange(socket, 20, Proto.message(Proto.bytes(1, encoding), Proto.int(3, 1)), 20)
        exchange(socket, 30, Proto.message(Proto.bytes(1, encoding), Proto.int(2, 1)), 31)

        mutableState.value = AndroidTvRemoteState(
            AndroidTvRemotePhase.AWAITING_CODE,
            host,
            "Введи шестисимвольный код, показанный телевизором"
        )
    }

    private fun startRemote(host: String) {
        mutableState.value = AndroidTvRemoteState(
            AndroidTvRemotePhase.CONNECTING,
            host,
            "Открываю защищённое соединение…"
        )
        val socket = tlsSocket(host, REMOTE_PORT, pairing = false)
        remoteSocket = socket
        readerJob = scope.launch {
            try {
                val input = socket.inputStream
                while (isActive && !socket.isClosed) {
                    val message = Proto.parse(Proto.frame(input))
                    when {
                        message.any { it.number == 1 } -> {
                            featureMask = message.nested(1).num(1) and SUPPORTED_FEATURES
                            check(featureMask and FEATURE_KEY != 0) {
                                "Телевизор не поддерживает команды пульта Android TV Remote"
                            }
                            val appInfo = Proto.message(
                                Proto.int(3, 1),
                                Proto.string(4, "1"),
                                Proto.string(5, "Universal Remote Control"),
                                Proto.string(6, "0.5.0")
                            )
                            send(Proto.bytes(1, Proto.message(
                                Proto.int(1, featureMask),
                                Proto.bytes(2, appInfo)
                            )))
                        }
                        message.any { it.number == 2 } -> {
                            send(Proto.bytes(2, Proto.int(1, featureMask)))
                        }
                        message.any { it.number == 8 } -> {
                            send(Proto.bytes(9, Proto.int(1, message.nested(8).num(1))))
                        }
                        message.any { it.number == 40 } -> {
                            socket.soTimeout = 0
                            mutableState.value = AndroidTvRemoteState(
                                AndroidTvRemotePhase.CONNECTED,
                                host,
                                "Подключено через Android TV Remote"
                            )
                        }
                        message.any { it.number == 3 } -> {
                            throw IllegalStateException("Телевизор отклонил команду")
                        }
                    }
                }
                if (isActive && remoteSocket === socket) {
                    throw IllegalStateException("Телевизор закрыл соединение")
                }
            } catch (error: Throwable) {
                if (isActive && remoteSocket === socket) {
                    remoteSocket = null
                    runCatching { socket.close() }
                    mutableState.value = AndroidTvRemoteState(
                        AndroidTvRemotePhase.ERROR,
                        host,
                        error.message?.take(180) ?: "Соединение с телевизором потеряно"
                    )
                }
            }
        }
    }

    private fun tlsSocket(host: String, port: Int, pairing: Boolean): SSLSocket {
        val (certificate, privateKey) = clientIdentity()
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf(CLIENT_ALIAS)
            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String = CLIENT_ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
        }

        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw java.security.cert.CertificateException("Client trust is not used")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (pairing) return
                val expected = preferences.getString(pinKey(host), null)
                    ?: throw java.security.cert.CertificateException("Требуется повторное сопряжение")
                val actual = fingerprint(chain.first())
                if (actual != expected) {
                    throw java.security.cert.CertificateException("Сертификат телевизора изменился. Удали устройство и сопряги снова.")
                }
            }
        }

        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keyManager), arrayOf(trustManager), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.soTimeout = SOCKET_TIMEOUT_MS
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.startHandshake()
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun clientIdentity(): Pair<X509Certificate, PrivateKey> {
        val (certificatePem, privateKeyPem) = KadbCert.getOrError()
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
        val keyText = privateKeyPem.toString(Charsets.US_ASCII)
        val base64 = keyText
            .substringAfter("-----BEGIN PRIVATE KEY-----")
            .substringBefore("-----END PRIVATE KEY-----")
            .filterNot(Char::isWhitespace)
        require(base64.isNotBlank()) { "Сохранённый ключ приложения повреждён" }
        val keyBytes = Base64.decode(base64, Base64.DEFAULT)
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        return certificate to privateKey
    }

    private fun exchange(socket: SSLSocket, field: Int, payload: ByteArray, expectedField: Int) {
        Proto.write(socket.outputStream, Proto.message(
            Proto.int(1, 2),
            Proto.int(2, 200),
            Proto.bytes(field, payload)
        ))
        val response = Proto.parse(Proto.frame(socket.inputStream))
        check(response.num(2) == 200 && response.any { it.number == expectedField }) {
            "Телевизор отклонил сопряжение"
        }
    }

    private fun send(bytes: ByteArray) {
        synchronized(lock) {
            val socket = remoteSocket ?: error("Нет соединения с телевизором")
            check(!socket.isClosed)
            Proto.write(socket.outputStream, bytes)
        }
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

    private fun pinKey(host: String) = "tv_pin:$host"

    private fun isPrivateIpv4(host: String): Boolean = runCatching {
        val parts = host.split('.').map(String::toInt)
        parts.size == 4 && parts.all { it in 0..255 } &&
            (parts[0] == 10 ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168))
    }.getOrDefault(false)

    private fun List<Proto.Field>.num(field: Int): Int =
        firstOrNull { it.number == field }?.value?.toInt() ?: 0

    private fun List<Proto.Field>.nested(field: Int): List<Proto.Field> =
        firstOrNull { it.number == field }?.bytes?.let { Proto.parse(it) } ?: emptyList()

    private object Proto {
        data class Field(val number: Int, val value: Long = 0, val bytes: ByteArray? = null)

        fun varint(value: Long): ByteArray {
            require(value >= 0)
            var number = value
            val output = java.io.ByteArrayOutputStream()
            while (number > 127) {
                output.write(((number and 127) or 128).toInt())
                number = number ushr 7
            }
            output.write(number.toInt())
            return output.toByteArray()
        }

        fun int(field: Int, value: Int): ByteArray =
            varint((field * 8).toLong()) + varint(value.toLong())

        fun bytes(field: Int, value: ByteArray): ByteArray =
            varint((field * 8 + 2).toLong()) + varint(value.size.toLong()) + value

        fun string(field: Int, value: String): ByteArray = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(vararg fields: ByteArray): ByteArray =
            fields.fold(ByteArray(0)) { result, field -> result + field }

        fun readVarint(input: InputStream): Long {
            var value = 0L
            for (index in 0..9) {
                val byte = input.read()
                require(byte >= 0) { "Телевизор закрыл соединение" }
                require(index < 9 || byte <= 1) { "Неверный protobuf varint" }
                value = value or ((byte and 127).toLong() shl (7 * index))
                if (byte and 128 == 0) return value
            }
            error("Неверный protobuf varint")
        }

        fun parse(bytes: ByteArray): List<Field> {
            val input = ByteArrayInputStream(bytes)
            val fields = mutableListOf<Field>()
            while (input.available() > 0) {
                val tag = readVarint(input).toInt()
                val number = tag ushr 3
                require(number > 0)
                when (tag and 7) {
                    0 -> fields += Field(number, readVarint(input))
                    2 -> {
                        val length = readVarint(input)
                        require(length in 0..MAX_FRAME_SIZE && length <= input.available())
                        val data = ByteArray(length.toInt())
                        require(input.read(data) == data.size)
                        fields += Field(number, bytes = data)
                    }
                    1 -> require(input.skip(8) == 8L)
                    5 -> require(input.skip(4) == 4L)
                    else -> error("Неподдерживаемый protobuf wire type")
                }
            }
            return fields
        }

        fun frame(input: InputStream): ByteArray {
            val length = readVarint(input)
            require(length in 1..MAX_FRAME_SIZE)
            val data = ByteArray(length.toInt())
            var offset = 0
            while (offset < data.size) {
                val read = input.read(data, offset, data.size - offset)
                require(read > 0) { "Телевизор закрыл соединение" }
                offset += read
            }
            return data
        }

        fun write(output: OutputStream, bytes: ByteArray) {
            output.write(varint(bytes.size.toLong()))
            output.write(bytes)
            output.flush()
        }
    }

    private companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        const val CONNECT_TIMEOUT_MS = 6000
        const val SOCKET_TIMEOUT_MS = 10000
        const val MAX_FRAME_SIZE = 1_048_576
        const val CLIENT_ALIAS = "universal-remote"
        const val KEY_SHORT = 3
        const val FEATURE_KEY = 2
        const val SUPPORTED_FEATURES = 1 or 2 or 4 or 32 or 64 or 512
    }
}
