package app.androidwebcam.core.cloud

import app.androidwebcam.core.Json
import app.androidwebcam.core.Logger
import app.androidwebcam.core.http.WebSocket
import app.androidwebcam.core.tls.Tls
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The phone's single outgoing connection, to the user's own cloud relay (only while streaming and
 * only when cloud access is turned on). The phone proves its identity by signing the relay's
 * random challenge with its hardware-backed key; then the relay forwards picture requests of cloud
 * agents, and the phone answers with pictures encrypted for the requesting agent ([CloudCrypto]).
 */
class RelayClient(
    relayUrl: String,
    private val identity: PhoneIdentity,
    private val agents: AgentDirectory,
    private val camera: () -> ByteArray?,
    private val listener: Listener,
    private val logger: Logger = Logger.NONE,
    private val connector: Connector = Connector.tls(),
    private val canConnect: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface PhoneIdentity {
        /** SubjectPublicKeyInfo of the phone's key (the relay checks its SHA-256 pin). */
        val spki: ByteArray

        /** ECDSA P-256 / SHA-256 signature of [message], raw r|s (64 bytes). */
        fun sign(message: ByteArray): ByteArray
    }

    data class Agent(val id: String, val authHash: String)

    interface AgentDirectory {
        fun list(): List<Agent>

        /** The agent's end-to-end key, or null if the agent does not exist (any more). */
        fun key(id: String): ByteArray?

        /** Null if the agent may take a picture now (the use is recorded), else 401 or 429. */
        fun admit(id: String): Int?
    }

    enum class Status { STOPPED, WAITING_FOR_NETWORK, CONNECTING, CONNECTED, REJECTED, RETRYING }

    interface Listener {
        fun onStatus(status: Status, detail: String?) {}
        fun onPicture(agentId: String) {}
    }

    fun interface Connector {
        fun open(host: String, port: Int, timeoutMs: Int): Socket

        companion object {
            /**
             * TLS with certificate and hostname checks (the only mode the app uses): the system's
             * trusted certificates, or those of [trust] (old Android versions, see [Tls.trustManager]),
             * with [Tls.provider] as the TLS implementation when set. [connectRaw] opens the TCP
             * connection (e.g. over a chosen network).
             */
            fun tls(
                connectRaw: (String, Int, Int) -> Socket = { host, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) } },
                trust: (() -> X509TrustManager)? = null,
            ): Connector = Connector { host, port, timeoutMs ->
                val raw = connectRaw(host, port, timeoutMs)
                try {
                    val context = if (trust == null && Tls.provider == null) {
                        SSLContext.getDefault()
                    } else {
                        Tls.context("TLS").apply { init(null, trust?.let { arrayOf(it()) }, null) }
                    }
                    val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                    // The bundled Conscrypt (Android 6-9) sends the server name given to createSocket;
                    // the platform parameters below do not exist before Android 7.
                    if (Tls.provider == null) requestNameChecks(ssl, host)
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                    // Checked here as well (the same on every platform), not only via the parameters above.
                    val certificate = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                    if (certificate == null || !certificateMatches(host, certificate)) {
                        ssl.close()
                        throw IOException("relay certificate does not match $host")
                    }
                    ssl
                } catch (e: Exception) {
                    try { raw.close() } catch (_: Exception) {}
                    throw e
                }
            }

            /** SNI and the TLS stack's own hostname check (Android 7+ and Java; see [tls]). */
            @Suppress("NewApi")
            private fun requestNameChecks(ssl: SSLSocket, host: String) {
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
            }

            /** RFC 6125 host check against the certificate's DNS names ("*." only as the whole first label). */
            fun certificateMatches(host: String, certificate: X509Certificate): Boolean {
                val name = host.lowercase().trimEnd('.')
                val dnsNames = certificate.subjectAlternativeNames?.filter { it.size >= 2 && it[0] == 2 }?.mapNotNull { it[1] as? String }.orEmpty()
                return dnsNames.any { pattern ->
                    val p = pattern.lowercase().trimEnd('.')
                    when {
                        p == name -> true
                        p.startsWith("*.") && p.indexOf('*', 1) < 0 -> {
                            val rest = p.substring(1) // ".example.com"
                            name.endsWith(rest) && name.length > rest.length && '.' !in name.substring(0, name.length - rest.length) &&
                                rest.count { it == '.' } >= 2
                        }
                        else -> false
                    }
                }
            }

            /** Plain TCP, for tests against a relay on this machine only. */
            val LOOPBACK_FOR_TESTS = Connector { host, port, timeoutMs ->
                require(host == "127.0.0.1" || host == "10.0.2.2") { "plain connections are for local tests only" }
                Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
            }
        }
    }

    private val uri = URI(CloudInstructions.normalizeRelay(relayUrl))
    private val host: String = uri.host ?: throw IllegalArgumentException("relay address")
    private val port: Int = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
    private val hostHeader = if (uri.port > 0) "$host:$port" else host
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var thread: Thread? = null

    init {
        require(uri.scheme == "https" || (uri.scheme == "http" && connector === Connector.LOOPBACK_FOR_TESTS)) { "the relay must use https" }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ run() }, "awc-relay").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        closeSocket()
        thread?.interrupt()
        thread = null
        listener.onStatus(Status.STOPPED, null)
    }

    /** Tells the relay about created or revoked agents right away (if connected). */
    fun agentsChanged() {
        val out = output ?: return
        try {
            send(out, WebSocket.OP_TEXT, agentsMessage("agents").toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            closeSocket()
        }
    }

    private fun run() {
        var delay = 0L
        while (running.get()) {
            if (delay > 0 && !sleep(delay)) break
            if (!canConnect()) {
                listener.onStatus(Status.WAITING_FOR_NETWORK, null)
                delay = 5_000
                continue
            }
            listener.onStatus(Status.CONNECTING, null)
            val started = clock()
            val outcome = try {
                session()
            } catch (e: Exception) {
                if (!running.get()) break
                logger.w("relay connection failed: ${e.javaClass.simpleName}")
                "${e.javaClass.simpleName}: ${e.message ?: ""}".take(160)
            } finally {
                closeSocket()
            }
            if (!running.get()) break
            if (outcome == REJECTED) {
                listener.onStatus(Status.REJECTED, "the relay does not accept this phone's key")
                delay = 5 * 60_000L
                continue
            }
            // Retry soon after a long-lived connection ended, with growing pauses after failures.
            delay = if (clock() - started > 60_000) 2_000 else minOf(maxOf(delay * 2, 2_000), 60_000)
            listener.onStatus(Status.RETRYING, outcome)
        }
    }

    /** One connection; returns why it ended. */
    private fun session(): String {
        val s = connector.open(host, port, CONNECT_TIMEOUT_MS)
        socket = s
        s.soTimeout = READ_TIMEOUT_MS // the relay answers our pings every 30 s
        val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
        val out = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
        handshake(input, out)

        // 1. Challenge from the relay: sign it (for this relay's host only) and present our key.
        val challenge = readText(input) ?: return "closed before challenge"
        if (Json.stringField(challenge, "type", 16) != "challenge" || Json.stringField(challenge, "protocol", 32) != PROTOCOL) {
            return "unexpected relay"
        }
        val nonce = Json.stringField(challenge, "nonce", 128) ?: return "unexpected relay"
        val signature = identity.sign("$PROTOCOL\n$hostHeader\n$nonce".toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        val auth = "{\"type\":\"auth\",\"spki\":${Json.quote(b64.encodeToString(identity.spki))}," +
            "\"signature\":${Json.quote(b64.encodeToString(signature))},${agentsMessage(null).removePrefix("{")}"
        send(out, WebSocket.OP_TEXT, auth.toByteArray(Charsets.UTF_8))

        // 2. Accepted, or refused (another key is configured on the relay).
        while (true) {
            val frame = WebSocket.readServerFrame(input, MAX_MESSAGE)
            when (frame.opcode) {
                WebSocket.OP_TEXT -> if (Json.stringField(String(frame.payload, Charsets.UTF_8), "type", 16) == "ready") break
                WebSocket.OP_CLOSE -> return if (closeCode(frame.payload) == 4401) REJECTED else "closed by relay (${closeCode(frame.payload)})"
                WebSocket.OP_PING -> send(out, WebSocket.OP_PONG, frame.payload)
            }
        }
        output = out
        listener.onStatus(Status.CONNECTED, null)

        // 3. Serve picture requests; keep the connection alive with pings.
        val pinger = Thread({
            try {
                while (running.get() && socket === s) {
                    Thread.sleep(PING_INTERVAL_MS)
                    send(out, WebSocket.OP_TEXT, PING)
                }
            } catch (_: Exception) {
            }
        }, "awc-relay-ping").apply { isDaemon = true; start() }
        try {
            while (running.get()) {
                val frame = WebSocket.readServerFrame(input, MAX_MESSAGE)
                when (frame.opcode) {
                    WebSocket.OP_TEXT -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        if (Json.stringField(text, "type", 16) == "snap") answer(out, text)
                    }
                    WebSocket.OP_PING -> send(out, WebSocket.OP_PONG, frame.payload)
                    WebSocket.OP_CLOSE -> return "closed by relay (${closeCode(frame.payload)})"
                }
            }
            return "stopped"
        } finally {
            output = null
            pinger.interrupt()
        }
    }

    private fun answer(out: OutputStream, request: String) {
        val id = Json.stringField(request, "id", 64)?.takeIf { it.matches(REQUEST_ID) } ?: return
        val agentId = Json.stringField(request, "agent", 16) ?: return
        fun fail(status: Int, error: String) =
            send(out, WebSocket.OP_TEXT, Json.obj("type" to Json.quote("error"), "id" to Json.quote(id), "status" to status.toString(), "error" to Json.quote(error)).toByteArray(Charsets.UTF_8))
        agents.admit(agentId)?.let { status ->
            fail(status, if (status == 429) "too many pictures, retry in a few seconds" else "unknown agent")
            return
        }
        val key = agents.key(agentId) ?: return fail(401, "unknown agent")
        val picture = try { camera() } catch (e: Exception) { null } ?: return fail(503, "no picture right now")
        val sealed = CloudCrypto.seal(key, agentId, clock(), picture, random)
        val idBytes = ByteArray(16) { i -> id.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        send(out, WebSocket.OP_BINARY, idBytes + sealed)
        listener.onPicture(agentId)
    }

    private fun agentsMessage(type: String?): String {
        val list = agents.list().joinToString(",", "[", "]") { Json.obj("id" to Json.quote(it.id), "hash" to Json.quote(it.authHash)) }
        return if (type != null) "{\"type\":${Json.quote(type)},\"agents\":$list}" else "{\"agents\":$list}"
    }

    private fun handshake(input: InputStream, out: OutputStream) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val request = "GET /phone HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: AndroidWebCam-Phone\r\n\r\n"
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
        val head = ByteArrayOutputStream()
        while (!head.toByteArray().endsWith(CRLF2)) {
            val b = input.read()
            if (b < 0 || head.size() > 16 * 1024) throw IOException("bad relay response")
            head.write(b)
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        if (lines.first().split(' ').getOrNull(1) != "101") throw IOException("relay answered ${lines.first().take(60)}")
        val accept = lines.drop(1).firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
        if (accept != WebSocket.acceptKey(key)) throw IOException("bad websocket handshake")
    }

    private fun readText(input: InputStream): String? {
        while (true) {
            val frame = WebSocket.readServerFrame(input, MAX_MESSAGE)
            when (frame.opcode) {
                WebSocket.OP_TEXT -> return String(frame.payload, Charsets.UTF_8)
                WebSocket.OP_CLOSE -> return null
            }
        }
    }

    private fun send(out: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            WebSocket.writeMaskedFrame(out, opcode, payload, random)
            out.flush()
        }
    }

    private fun closeSocket() {
        output = null
        val s = socket ?: return
        socket = null
        try { s.close() } catch (_: Exception) {}
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun closeCode(payload: ByteArray): Int =
        if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    companion object {
        const val PROTOCOL = "AWC-RELAY-1"
        private const val REJECTED = "rejected"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 75_000
        private const val PING_INTERVAL_MS = 30_000L
        private const val MAX_MESSAGE = 64 * 1024
        private val PING = "ping".toByteArray(Charsets.US_ASCII)
        private val CRLF2 = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private val REQUEST_ID = Regex("[0-9a-f]{32}")
    }
}
