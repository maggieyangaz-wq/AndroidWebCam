package app.androidwebcam.core.server

import app.androidwebcam.core.Json
import app.androidwebcam.core.Logger
import app.androidwebcam.core.auth.AccessControl
import app.androidwebcam.core.auth.AiKeys
import app.androidwebcam.core.auth.LoginResult
import app.androidwebcam.core.http.HttpException
import app.androidwebcam.core.http.HttpParser
import app.androidwebcam.core.http.HttpRequest
import app.androidwebcam.core.http.HttpResponse
import app.androidwebcam.core.http.WebSocket
import app.androidwebcam.core.stream.EncodedFrame
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.stream.VideoConfig
import app.androidwebcam.core.tls.IdentityKeyManager
import app.androidwebcam.core.tls.Tls
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The phone-side HTTPS server (TLS 1.3 only). It serves the viewer page, handles
 * authentication and streams H.264 over WebSocket (decoded by WebCodecs in the browser)
 * or MJPEG as a fallback; single pictures (/api/snapshot) serve AI tools on a computer
 * in the same network. Every request is checked for:
 *  - allowed network path (decided by [ConnectionPolicy] before the TLS handshake);
 *  - a Host header naming this server's IP:port (DNS-rebinding defense);
 *  - Origin on state-changing requests and WebSocket upgrades (CSRF / CSWSH defense);
 *  - a valid session cookie bound to the viewer's IP for anything that shows video.
 */
class WebCamServer(
    private val options: Options,
    private val keyManager: IdentityKeyManager,
    private val access: AccessControl,
    private val hub: StreamHub,
    private val policy: ConnectionPolicy,
    private val listener: Listener = object : Listener {},
    private val logger: Logger = Logger.NONE,
    /** AI access keys for single pictures (persisted by the app across streaming sessions). */
    private val aiKeys: AiKeys = AiKeys(),
) {
    data class Options(
        val port: Int,
        val maxViewers: Int = 4,
        val maxConnections: Int = 32,
        /** A single LAN device cannot take all connection slots. */
        val maxConnectionsPerAddress: Int = 12,
        /** Request headers must be complete this long after their first byte. */
        val requestHeaderDeadlineMs: Long = 10_000,
        val appVersion: String = "dev",
        val mjpegAvailable: Boolean = true,
        /** How long a snapshot request waits for the next picture. */
        val snapshotWaitMs: Long = 10_000,
        /** Minimum time between two snapshots for one network address. */
        val snapshotIntervalMs: Long = 1_000,
    )

    fun interface ConnectionPolicy {
        /** Called before the TLS handshake for every TCP connection. */
        fun allow(local: InetAddress, remote: InetAddress): Boolean
    }

    interface Listener {
        fun onViewersChanged(viewers: List<Viewer>) {}
        fun onConnectionRejected(remote: String) {}
        /** A logged-in client (e.g. an AI tool) took a single picture. */
        fun onSnapshot(address: String, client: String) {}
        /** A logged-in viewer created an AI access key for [address]. */
        fun onAiKeyCreated(address: String) {}
    }

    data class Viewer(val id: Int, val address: String, val client: String, val mode: String, val sinceEpochMs: Long)

    private class Exchange(
        val socket: SSLSocket,
        val request: HttpRequest,
        val input: InputStream,
        val output: OutputStream,
        /** Validated "ip:port" this request was addressed to, or null if invalid. */
        val authority: String?,
    ) {
        val remote: InetAddress get() = socket.inetAddress
    }

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: SSLServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val perAddress = ConcurrentHashMap<InetAddress, Int>()
    private val viewers = ConcurrentHashMap<Int, Viewer>()
    private val viewerSockets = ConcurrentHashMap<Int, Socket>()
    private val viewerSeq = AtomicInteger(1)
    private val lastSnapshot = ConcurrentHashMap<InetAddress, Long>()
    private val watchdog = WriteWatchdog()
    private val assets = StaticAssets.load()

    val port: Int get() = serverSocket?.localPort ?: options.port
    val isRunning: Boolean get() = running.get()

    @Throws(IOException::class)
    fun start() {
        check(running.compareAndSet(false, true)) { "already started" }
        try {
            val context = Tls.serverContext(keyManager)
            val ss = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                ss.reuseAddress = true
                Tls.hardenServerSocket(ss)
                // IPv4 only: LAN viewers use the phone's private IPv4 address.
                ss.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), options.port), 64)
            } catch (e: IOException) {
                closeQuietly(ss)
                throw e
            }
            serverSocket = ss
            executor = ThreadPoolExecutor(0, options.maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue(), daemonThreads("awc-http"))
            watchdog.start()
            Thread({ acceptLoop(ss) }, "awc-accept").apply { isDaemon = true }.start()
            logger.i("server listening on port ${ss.localPort}")
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(serverSocket)
        for (s in sockets) closeQuietly(s)
        sockets.clear()
        perAddress.clear()
        executor?.shutdownNow()
        watchdog.stop()
        viewers.clear()
        viewerSockets.clear()
        lastSnapshot.clear()
        listener.onViewersChanged(emptyList())
    }

    /** Immediately drops every streaming viewer (sessions are handled by [AccessControl]). */
    fun disconnectAllViewers() {
        for (s in viewerSockets.values) closeQuietly(s)
    }

    fun currentViewers(): List<Viewer> = viewers.values.sortedBy { it.id }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running.get()) logger.w("accept failed", e)
                continue
            }
            val local = socket.localAddress
            val remote = socket.inetAddress
            if (!policy.allow(local, remote)) {
                closeQuietly(socket)
                listener.onConnectionRejected(remote.hostAddress ?: "?")
                continue
            }
            if (sockets.size >= options.maxConnections || !reserveSlot(remote)) {
                closeQuietly(socket)
                continue
            }
            sockets.add(socket)
            try {
                executor?.execute { handleConnection(socket as SSLSocket) } ?: run {
                    sockets.remove(socket)
                    releaseSlot(remote)
                    closeQuietly(socket)
                }
            } catch (e: RejectedExecutionException) {
                sockets.remove(socket)
                releaseSlot(remote)
                closeQuietly(socket)
            }
        }
    }

    private fun reserveSlot(remote: InetAddress): Boolean {
        var ok = false
        perAddress.compute(remote) { _, current ->
            val n = current ?: 0
            if (n < options.maxConnectionsPerAddress) {
                ok = true
                n + 1
            } else {
                n
            }
        }
        return ok
    }

    private fun releaseSlot(remote: InetAddress) {
        perAddress.computeIfPresent(remote) { _, n -> if (n <= 1) null else n - 1 }
    }

    private fun handleConnection(socket: SSLSocket) {
        val remote = socket.inetAddress
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            val input = BufferedInputStream(socket.inputStream, 8 * 1024)
            val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
            var served = 0
            while (running.get() && served < MAX_REQUESTS_PER_CONNECTION) {
                socket.soTimeout = if (served == 0) REQUEST_TIMEOUT_MS else KEEP_ALIVE_TIMEOUT_MS
                val request = try {
                    HttpParser.read(input, options.requestHeaderDeadlineMs) ?: break
                } catch (e: HttpException) {
                    val r = HttpResponse(e.status).text(e.status.toString() + "\n")
                    addSecurityHeaders(r, null)
                    r.writeTo(output, keepAlive = false)
                    break
                }
                served++
                val authority = validAuthority(request, socket.localAddress)
                if (!dispatch(Exchange(socket, request, input, output, authority))) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            // stop() interrupts connections that wait for the next picture: just end this one.
            // (Escaping this thread, the exception would end the whole app.)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            logger.w("connection failed", e)
        } finally {
            sockets.remove(socket)
            releaseSlot(remote)
            closeQuietly(socket)
        }
    }

    /** Returns true to keep the connection open for another request. */
    private fun dispatch(x: Exchange): Boolean {
        if (x.authority == null) return respond(x, error(421), keepAlive = false)
        val method = x.request.method
        return when (val path = x.request.path) {
            "/", "/index.html" -> onlyGet(x) { serveAsset(x, "index.html") }
            "/app.js", "/style.css", "/favicon.png" -> onlyGet(x) { serveAsset(x, path.substring(1)) }
            "/$AI_BRIDGE" -> onlyGet(x) { serveAsset(x, AI_BRIDGE, download = true) }
            "/robots.txt" -> onlyGet(x) { respond(x, HttpResponse(200).text("User-agent: *\nDisallow: /\n")) }
            "/api/status" -> onlyGet(x) { status(x) }
            "/api/login" -> onlyPost(x) { login(x) }
            "/api/pair" -> onlyPost(x) { pair(x) }
            "/api/logout" -> onlyPost(x) { logout(x) }
            "/ws" -> onlyGet(x) { websocket(x) }
            "/mjpeg" -> onlyGet(x) { mjpeg(x) }
            "/api/snapshot" -> onlyGet(x) { snapshot(x) }
            "/api/ai-key" -> onlyPost(x) { createAiKey(x) }
            else -> if (method == "GET" || method == "POST") respond(x, error(404)) else respond(x, error(405).header("Allow", "GET, POST"))
        }
    }

    private inline fun onlyGet(x: Exchange, handler: () -> Boolean): Boolean =
        if (x.request.method == "GET") handler() else respond(x, error(405).header("Allow", "GET"))

    private inline fun onlyPost(x: Exchange, handler: () -> Boolean): Boolean =
        if (x.request.method == "POST") handler() else respond(x, error(405).header("Allow", "POST"))

    // ---------------------------------------------------------------- handlers

    private fun serveAsset(x: Exchange, name: String, download: Boolean = false): Boolean {
        val asset = assets[name] ?: return respond(x, error(404))
        val response = HttpResponse(200).body(asset.bytes, asset.contentType)
        if (download) response.header("Content-Disposition", "attachment; filename=\"$name\"")
        return respond(x, response)
    }

    private fun status(x: Exchange): Boolean {
        authenticate(x) ?: return respond(x, unauthorized())
        val cfg = hub.config
        val video = if (cfg == null) "null" else Json.obj(
            "codec" to Json.quote(cfg.codec),
            "width" to cfg.width.toString(),
            "height" to cfg.height.toString(),
        )
        val identity = keyManager.identity
        val json = Json.obj(
            "app" to Json.quote("Android WebCam"),
            "version" to Json.quote(options.appVersion),
            "video" to video,
            "mjpeg" to options.mjpegAvailable.toString(),
            "viewers" to viewers.size.toString(),
            "maxViewers" to options.maxViewers.toString(),
            // For the AI tool setup shown to logged-in viewers (both values are public).
            "fingerprint" to Json.quote(identity.fingerprintSha256),
            "pin" to Json.quote(identity.publicKeyPin),
        )
        return respond(x, HttpResponse(200).json(json))
    }

    private fun login(x: Exchange): Boolean {
        if (!sameOrigin(x)) return respond(x, error(403))
        if (!isJson(x.request)) return respond(x, error(415))
        val code = Json.stringField(String(x.request.body, Charsets.UTF_8), "code", 64) ?: return respond(x, error(400))
        return respondLogin(x, access.login(code, x.remote, UserAgents.describe(x.request.header("user-agent"))))
    }

    private fun pair(x: Exchange): Boolean {
        if (!sameOrigin(x)) return respond(x, error(403))
        if (!isJson(x.request)) return respond(x, error(415))
        val token = Json.stringField(String(x.request.body, Charsets.UTF_8), "token", 64) ?: return respond(x, error(400))
        return respondLogin(x, access.pair(token, x.remote, UserAgents.describe(x.request.header("user-agent"))))
    }

    private fun logout(x: Exchange): Boolean {
        if (!sameOrigin(x)) return respond(x, error(403))
        access.logout(x.request.cookie(AccessControl.SESSION_COOKIE))
        return respond(
            x,
            HttpResponse(204).header("Set-Cookie", "${AccessControl.SESSION_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0"),
        )
    }

    private fun respondLogin(x: Exchange, result: LoginResult): Boolean = when (result) {
        is LoginResult.Success -> respond(
            x,
            HttpResponse(204).header(
                "Set-Cookie",
                "${AccessControl.SESSION_COOKIE}=${result.sessionToken}; Path=/; Secure; HttpOnly; SameSite=Strict",
            ),
        )
        is LoginResult.Invalid -> respond(
            x,
            HttpResponse(401).json(Json.obj("error" to Json.quote("invalid"), "attemptsLeft" to result.attemptsBeforeLock.toString())),
        )
        is LoginResult.Locked -> respond(
            x,
            HttpResponse(429).header("Retry-After", result.retryAfterSeconds.toString())
                .json(Json.obj("error" to Json.quote("locked"), "retryAfter" to result.retryAfterSeconds.toString())),
        )
    }

    private fun websocket(x: Exchange): Boolean {
        val token = x.request.cookie(AccessControl.SESSION_COOKIE)
        authenticate(x) ?: return respond(x, unauthorized(), keepAlive = false)
        if (!sameOrigin(x)) return respond(x, error(403), keepAlive = false)
        val accept = WebSocket.validateUpgrade(x.request) ?: return respond(x, error(400), keepAlive = false)
        val viewer = registerViewer(x, "H.264") ?: return respond(x, busy(), keepAlive = false)
        try {
            HttpResponse(101)
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Accept", accept)
                .writeTo(x.output, keepAlive = true)
            streamVideo(x, token!!)
        } finally {
            unregisterViewer(viewer)
        }
        return false
    }

    private fun streamVideo(x: Exchange, token: String) {
        val socket = x.socket
        val out = x.output
        val writeLock = Any()
        val open = AtomicBoolean(true)
        val subscription = hub.subscribeVideo()
        socket.soTimeout = WS_IDLE_TIMEOUT_MS // the viewer sends a ping every few seconds
        val reader = Thread({
            var closeCode = 1000
            try {
                while (open.get()) {
                    val frame = WebSocket.readFrame(x.input, 4096)
                    when (frame.opcode) {
                        WebSocket.OP_TEXT -> when (Json.stringField(String(frame.payload, Charsets.UTF_8), "type", 32)) {
                            "keyframe" -> subscription.resync()
                            // Answered even while no pictures flow (e.g. the camera is busy): a viewer that
                            // gets no answer knows at once that the phone is gone and stops showing the
                            // last picture as if it were live.
                            "ping" -> synchronized(writeLock) {
                                watchdog.guard(socket) {
                                    WebSocket.writeFrame(out, WebSocket.OP_TEXT, PONG)
                                    out.flush()
                                }
                            }
                            else -> Unit // unknown messages only prove liveness
                        }
                        WebSocket.OP_PING -> synchronized(writeLock) {
                            WebSocket.writeFrame(out, WebSocket.OP_PONG, frame.payload)
                            out.flush()
                        }
                        WebSocket.OP_PONG -> Unit
                        WebSocket.OP_CLOSE -> break
                        else -> throw WebSocket.ProtocolException(1003, "unsupported frame")
                    }
                }
            } catch (e: WebSocket.ProtocolException) {
                closeCode = e.closeCode
            } catch (_: Exception) {
                closeCode = -1
            } finally {
                if (open.getAndSet(false) && closeCode > 0) {
                    try {
                        synchronized(writeLock) {
                            WebSocket.writeFrame(out, WebSocket.OP_CLOSE, WebSocket.closePayload(closeCode, ""))
                            out.flush()
                        }
                    } catch (_: Exception) {
                    }
                }
                closeQuietly(socket)
            }
        }, "awc-ws-reader").apply { isDaemon = true }
        reader.start()

        try {
            var lastAuthCheck = 0L
            while (open.get() && running.get()) {
                val now = monotonicMillis()
                if (now - lastAuthCheck >= 1000) {
                    lastAuthCheck = now
                    if (access.authenticate(token, x.remote) == null) {
                        synchronized(writeLock) {
                            WebSocket.writeFrame(out, WebSocket.OP_CLOSE, WebSocket.closePayload(4401, "session ended"))
                            out.flush()
                        }
                        break
                    }
                }
                val item = subscription.next(500) ?: continue
                synchronized(writeLock) {
                    watchdog.guard(socket) {
                        when (item) {
                            is VideoConfig -> WebSocket.writeFrame(out, WebSocket.OP_TEXT, configMessage(item).toByteArray(Charsets.UTF_8))
                            is EncodedFrame -> WebSocket.writeFrame(out, WebSocket.OP_BINARY, item.data, frameHeader(item))
                        }
                        out.flush()
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            open.set(false)
            subscription.close()
            closeQuietly(socket)
        }
    }

    private fun mjpeg(x: Exchange): Boolean {
        val token = x.request.cookie(AccessControl.SESSION_COOKIE)
        authenticate(x) ?: return respond(x, unauthorized(), keepAlive = false)
        if (!options.mjpegAvailable) return respond(x, error(404), keepAlive = false)
        val site = x.request.header("sec-fetch-site")
        if (site != null && site != "same-origin") return respond(x, error(403), keepAlive = false)
        val viewer = registerViewer(x, "MJPEG") ?: return respond(x, busy(), keepAlive = false)
        val socket = x.socket
        val out = x.output
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: multipart/x-mixed-replace; boundary=").append(BOUNDARY).append("\r\n")
            for ((k, v) in securityHeaderList(x.authority)) head.append(k).append(": ").append(v).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val subscription = hub.subscribeMjpeg()
            try {
                var lastAuthCheck = 0L
                while (running.get()) {
                    val now = monotonicMillis()
                    if (now - lastAuthCheck >= 1000) {
                        lastAuthCheck = now
                        if (access.authenticate(token, x.remote) == null) break
                    }
                    val jpeg = subscription.next(500) ?: continue
                    watchdog.guard(socket) {
                        out.write(("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        out.write(jpeg)
                        out.write(CRLF)
                        out.flush()
                    }
                }
            } finally {
                subscription.close()
            }
        } catch (_: IOException) {
        } finally {
            unregisterViewer(viewer)
        }
        return false
    }

    /**
     * One current picture as JPEG (rotated like the stream, never mirrored), for AI tools on a
     * computer in the same network. Same session rules as the video; at most one picture per
     * second per address, so it cannot be used as an unlimited extra stream.
     */
    private fun snapshot(x: Exchange): Boolean {
        // Either an AI access key (agents, e.g. curl) or a viewer session cookie.
        val bearer = x.request.header("authorization")
            ?.takeIf { it.length > 7 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }
            ?.substring(7)?.trim()
        val client = if (bearer != null) {
            if (!aiKeys.verify(bearer, x.remote)) return respond(x, unauthorized())
            "AI key · " + UserAgents.describe(x.request.header("user-agent"))
        } else {
            (authenticate(x) ?: return respond(x, unauthorized())).client
        }
        if (!options.mjpegAvailable) return respond(x, error(404))
        val site = x.request.header("sec-fetch-site")
        if (site != null && site != "same-origin") return respond(x, error(403))
        val now = monotonicMillis()
        var allowed = false
        lastSnapshot.compute(x.remote) { _, last ->
            if (last == null || now - last >= options.snapshotIntervalMs) {
                allowed = true
                now
            } else {
                last
            }
        }
        if (!allowed) {
            return respond(x, HttpResponse(429).header("Retry-After", "1").json(Json.obj("error" to Json.quote("too-fast"))))
        }
        if (lastSnapshot.size > 256) lastSnapshot.entries.removeIf { now - it.value > options.snapshotIntervalMs }
        // Subscribing starts JPEG encoding if no MJPEG viewer is connected; the next picture is fresh.
        val subscription = hub.subscribeMjpeg()
        val jpeg = try {
            subscription.next(options.snapshotWaitMs)
        } finally {
            subscription.close()
        } ?: return respond(x, HttpResponse(503).header("Retry-After", "2").json(Json.obj("error" to Json.quote("no-picture"))))
        listener.onSnapshot(x.remote.hostAddress ?: "?", client)
        return respond(x, HttpResponse(200).body(jpeg, "image/jpeg"))
    }

    /**
     * Creates an AI access key for the requesting computer (logged-in viewers only, same-origin).
     * The key is shown once; creating another one for the same computer replaces it.
     */
    private fun createAiKey(x: Exchange): Boolean {
        authenticate(x) ?: return respond(x, unauthorized())
        if (!sameOrigin(x)) return respond(x, error(403))
        if (!isJson(x.request)) return respond(x, error(415))
        val key = aiKeys.create(x.remote)
        listener.onAiKeyCreated(x.remote.hostAddress ?: "?")
        return respond(x, HttpResponse(200).json(Json.obj("key" to Json.quote(key))))
    }

    // ---------------------------------------------------------------- helpers

    private fun authenticate(x: Exchange): AccessControl.Session? =
        access.authenticate(x.request.cookie(AccessControl.SESSION_COOKIE), x.remote)

    private fun validAuthority(request: HttpRequest, local: InetAddress): String? {
        val host = request.header("host") ?: return null
        val expected = "${local.hostAddress}:$port"
        return if (host.equals(expected, ignoreCase = true)) expected else null
    }

    private fun sameOrigin(x: Exchange): Boolean {
        val origin = x.request.header("origin") ?: return false
        if (origin != "https://${x.authority}") return false
        val site = x.request.header("sec-fetch-site")
        return site == null || site == "same-origin"
    }

    private fun isJson(request: HttpRequest): Boolean =
        request.header("content-type")?.lowercase()?.startsWith("application/json") == true

    private fun registerViewer(x: Exchange, mode: String): Viewer? {
        val viewer: Viewer
        synchronized(viewers) {
            if (viewers.size >= options.maxViewers) return null
            viewer = Viewer(
                viewerSeq.getAndIncrement(),
                x.remote.hostAddress ?: "?",
                UserAgents.describe(x.request.header("user-agent")),
                mode,
                System.currentTimeMillis(),
            )
            viewers[viewer.id] = viewer
            viewerSockets[viewer.id] = x.socket
        }
        listener.onViewersChanged(currentViewers())
        return viewer
    }

    private fun unregisterViewer(viewer: Viewer) {
        synchronized(viewers) {
            viewers.remove(viewer.id)
            viewerSockets.remove(viewer.id)
        }
        if (running.get()) listener.onViewersChanged(currentViewers())
    }

    private fun configMessage(cfg: VideoConfig): String = Json.obj(
        "type" to Json.quote("config"),
        "codec" to Json.quote(cfg.codec),
        "width" to cfg.width.toString(),
        "height" to cfg.height.toString(),
        "description" to Json.quote(Base64.getEncoder().encodeToString(cfg.description)),
    )

    /** 10-byte frame header: type(1)=1, flags(1) bit0=key, timestamp µs as uint32 hi + uint32 lo. */
    private fun frameHeader(frame: EncodedFrame): ByteArray {
        val ts = frame.timestampUs
        val h = ByteArray(10)
        h[0] = 1
        h[1] = if (frame.isKey) 1 else 0
        for (i in 0 until 8) h[2 + i] = (ts ushr (56 - 8 * i)).toByte()
        return h
    }

    private fun error(status: Int): HttpResponse = HttpResponse(status).text("$status\n")

    private fun unauthorized(): HttpResponse = HttpResponse(401).json(Json.obj("error" to Json.quote("unauthorized")))

    private fun busy(): HttpResponse = HttpResponse(503).header("Retry-After", "10").json(Json.obj("error" to Json.quote("busy")))

    private fun respond(x: Exchange, response: HttpResponse, keepAlive: Boolean = true): Boolean {
        addSecurityHeaders(response, x.authority)
        val keep = keepAlive && x.request.keepAlive && running.get()
        response.writeTo(x.output, keep)
        return keep
    }

    private fun addSecurityHeaders(response: HttpResponse, authority: String?) {
        for ((k, v) in securityHeaderList(authority)) response.header(k, v)
    }

    private fun securityHeaderList(authority: String?): List<Pair<String, String>> {
        val connect = if (authority != null) "'self' wss://$authority" else "'self'"
        return listOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; " +
                "connect-src $connect; manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
            "Cache-Control" to "no-store",
            "Pragma" to "no-cache",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Resource-Policy" to "same-origin",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), usb=(), payment=(), interest-cohort=()",
            "X-Robots-Tag" to "noindex, nofollow",
        )
    }

    companion object {
        /** Zero-dependency MCP server for AI clients on a computer (see README). */
        const val AI_BRIDGE = "awc-ai.mjs"
        private const val BOUNDARY = "awcframe"
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val PONG = "{\"type\":\"pong\"}".toByteArray(Charsets.UTF_8)
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val REQUEST_TIMEOUT_MS = 10_000
        const val KEEP_ALIVE_TIMEOUT_MS = 15_000
        const val WS_IDLE_TIMEOUT_MS = 30_000
        const val MAX_REQUESTS_PER_CONNECTION = 100
    }
}
