package com.universalremote.app.connection

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
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
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

    val connectedHost: String? get() = activeHost

    init {
        AdbIdentity.configure(appContext)
    }

    fun hasPairing(host: String): Boolean =
        preferences.getString(pinKey(host), null) != null

    suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        require(NetworkUtils.isPrivateIpv4(host)) {
            "Разрешено подключение только к телевизору в локальной сети"
        }
        disconnect()
        activeHost = host
        mutableState.value = AndroidTvRemoteState(
            AndroidTvRemotePhase.CONNECTING,
            host,
            "Подключаюсь к Android TV Remote…"
        )

        try {
            if (!hasPairing(host)) {
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
        val normalized = code.trim().uppercase()
        require(normalized.matches(PairingCrypto.CODE_PATTERN)) {
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

            val digest = PairingCrypto.digest(clientKey, serverKey, normalized)
            require(PairingCrypto.matchesPrefix(digest, normalized)) {
                "Код не совпадает с телевизором. Проверь код на экране ТВ."
            }

            exchange(socket, 40, RemoteWire.bytes(1, digest), 41)
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
        sendKeyEvent(androidKeyCode, RemoteWire.KEY_SHORT)
    }

    suspend fun sendKeyDown(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        sendKeyEvent(androidKeyCode, RemoteWire.KEY_DOWN)
    }

    suspend fun sendKeyUp(androidKeyCode: Int) = withContext(Dispatchers.IO) {
        sendKeyEvent(androidKeyCode, RemoteWire.KEY_UP)
    }

    /** Launch a previously selected TV app using its validated Android package identifier. */
    suspend fun launchApp(packageName: String) = withContext(Dispatchers.IO) {
        check(isConnected()) { "Сначала подключись к телевизору" }
        check(featureMask and FEATURE_APPS != 0) {
            "Этот телевизор не предоставляет запуск приложений через Android TV Remote"
        }
        send(RemoteWire.appLink(packageName))
    }

    fun isConnected(): Boolean {
        val socket = remoteSocket ?: return false
        return mutableState.value.phase == AndroidTvRemotePhase.CONNECTED &&
            socket.isConnected && !socket.isClosed
    }

    fun disconnect() {
        closeSockets()
        activeHost = null
        featureMask = 0
        mutableState.value = AndroidTvRemoteState()
    }

    fun forget(host: String? = activeHost) {
        host?.let { preferences.edit().remove(pinKey(it)).commit() }
        if (host == null || host == activeHost) disconnect()
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    /** Close pairing and control channels without changing the selected TV identity. */
    private fun closeSockets() {
        readerJob?.cancel()
        readerJob = null
        val pair = pairingSocket
        val remote = remoteSocket
        pairingSocket = null
        remoteSocket = null
        runCatching { pair?.close() }
        runCatching { remote?.close() }
    }

    private fun sendKeyEvent(androidKeyCode: Int, direction: Int) {
        require(androidKeyCode in 0..300) { "Некорректная команда" }
        check(isConnected()) { "Сначала подключись к телевизору" }
        send(RemoteWire.key(androidKeyCode, direction))
    }

    private fun beginPairing(host: String) {
        val socket = tlsSocket(host, PAIRING_PORT, pairing = true)
        pairingSocket = socket

        exchange(
            socket,
            10,
            RemoteWire.message(
                RemoteWire.string(1, "atvremote"),
                RemoteWire.string(2, "Universal Remote Control")
            ),
            11
        )
        val encoding = RemoteWire.message(RemoteWire.int(1, 3), RemoteWire.int(2, 6))
        exchange(socket, 20, RemoteWire.message(RemoteWire.bytes(1, encoding), RemoteWire.int(3, 1)), 20)
        exchange(socket, 30, RemoteWire.message(RemoteWire.bytes(1, encoding), RemoteWire.int(2, 1)), 31)

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
                    val message = RemoteWire.parse(RemoteWire.frame(input))
                    when {
                        message.any { it.number == 1 } -> {
                            featureMask = message.nested(1).num(1) and SUPPORTED_FEATURES
                            check(featureMask and FEATURE_KEY != 0) {
                                "Телевизор не поддерживает команды пульта Android TV Remote"
                            }
                            val appInfo = RemoteWire.message(
                                RemoteWire.int(3, 1),
                                RemoteWire.string(4, "1"),
                                RemoteWire.string(5, "com.universalremote.app"),
                                RemoteWire.string(6, APP_VERSION)
                            )
                            send(RemoteWire.bytes(1, RemoteWire.message(
                                RemoteWire.int(1, featureMask),
                                RemoteWire.bytes(2, appInfo)
                            )))
                        }
                        message.any { it.number == 2 } -> {
                            send(RemoteWire.bytes(2, RemoteWire.int(1, featureMask)))
                        }
                        message.any { it.number == 8 } -> {
                            send(RemoteWire.bytes(9, RemoteWire.int(1, message.nested(8).num(1))))
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
                    throw java.security.cert.CertificateException(
                        "Сертификат телевизора изменился. Удали устройство и сопряги снова."
                    )
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
            throw remoteConnectError(host, port, error)
        }
    }

    private fun remoteConnectError(host: String, port: Int, error: Throwable): Throwable {
        val raw = error.message.orEmpty()
        // Keep the certificate/pairing messages produced by the trust manager.
        if (raw.contains("Сертификат") || raw.contains("повторное сопряжение", true)) return error
        val message = when {
            error is ConnectException ||
                raw.contains("ECONNREFUSED", true) ||
                raw.contains("Connection refused", true) -> {
                if (port == PAIRING_PORT) {
                    "Android TV Remote: порт сопряжения 6467 закрыт. На ТВ открой «Пульты и аксессуары» → " +
                        "«Добавить аксессуар» или подключись по ADB."
                } else {
                    "Android TV Remote: порт управления 6466 закрыт. Служба пульта не запущена — подключись по ADB."
                }
            }
            error is SocketTimeoutException || raw.contains("timed out", true) -> {
                "Телевизор не ответил по Android TV Remote ($host:$port). Проверь, что он включён и в той же сети."
            }
            error is SSLException -> {
                "Не удалось установить защищённое соединение с телевизором: ${raw.take(110)}"
            }
            else -> return error
        }
        return IllegalStateException(message, error)
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
        RemoteWire.write(
            socket.outputStream,
            RemoteWire.message(
                RemoteWire.int(1, 2),
                RemoteWire.int(2, 200),
                RemoteWire.bytes(field, payload)
            )
        )
        val response = RemoteWire.parse(RemoteWire.frame(socket.inputStream))
        check(response.num(2) == 200 && response.any { it.number == expectedField }) {
            "Телевизор отклонил сопряжение"
        }
    }

    private fun send(bytes: ByteArray) {
        synchronized(lock) {
            val socket = remoteSocket ?: error("Нет соединения с телевизором")
            check(!socket.isClosed)
            RemoteWire.write(socket.outputStream, bytes)
        }
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun pinKey(host: String) = "tv_pin:$host"

    private companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        const val CONNECT_TIMEOUT_MS = 6000
        const val SOCKET_TIMEOUT_MS = 10000
        const val CLIENT_ALIAS = "universal-remote"
        const val KEY_SHORT = RemoteWire.KEY_SHORT
        const val FEATURE_KEY = 2
        const val FEATURE_APPS = 512
        const val SUPPORTED_FEATURES = 1 or 2 or 32 or 64 or 512
        const val APP_VERSION = "0.5.0"
    }
}
