package app.androidwebcam.core

import app.androidwebcam.core.auth.AccessControl
import app.androidwebcam.core.http.WebSocket
import app.androidwebcam.core.server.WebCamServer
import app.androidwebcam.core.stream.EncodedFrame
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.stream.VideoConfig
import app.androidwebcam.core.tls.IdentityKeyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

class ServerIntegrationTest {
    private val identity = TestSupport.softwareIdentity()
    private val access = AccessControl()
    private val allow = AtomicBoolean(true)
    private val hub = StreamHub(object : StreamHub.Listener {})
    private val snapshots = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var server: WebCamServer
    private lateinit var host: String

    @Before
    fun setUp() {
        server = WebCamServer(
            WebCamServer.Options(port = 0, appVersion = "test", maxConnectionsPerAddress = 6, requestHeaderDeadlineMs = 500, snapshotWaitMs = 1500),
            IdentityKeyManager(identity),
            access,
            hub,
            { _, _ -> allow.get() },
            object : WebCamServer.Listener {
                override fun onSnapshot(address: String, client: String) {
                    snapshots.add("$address $client")
                }
            },
        )
        server.start()
        host = "127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() = server.stop()

    private fun connect(protocol: String = "TLSv1.3"): TestSupport.Client {
        val socket = TestSupport.clientContext(identity, protocol).socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress("127.0.0.1", server.port), 5000)
        socket.soTimeout = 5000
        if (protocol != "TLSv1.3") socket.enabledProtocols = arrayOf(protocol)
        socket.startHandshake()
        return TestSupport.Client(socket)
    }

    private fun json(body: String) = mapOf("Origin" to "https://$host", "Content-Type" to "application/json") to body

    private fun login(client: TestSupport.Client): String {
        val (headers, body) = json("{\"code\":\"${access.displayCode}\"}")
        val r = client.request("POST", "/api/login", host, headers, body)
        assertEquals(204, r.status)
        val cookie = r.header("set-cookie")!!
        assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
        return cookie.substringBefore(';')
    }

    @Test
    fun servesViewerPageWithStrictSecurityHeaders() {
        connect().use { c ->
            val r = c.request("GET", "/", host)
            assertEquals(200, r.status)
            assertTrue(r.text.contains("app.js"))
            val csp = r.header("content-security-policy")!!
            assertTrue(csp.contains("default-src 'none'") && csp.contains("frame-ancestors 'none'"))
            assertTrue(csp.contains("connect-src 'self' wss://$host"))
            assertEquals("no-store", r.header("cache-control"))
            assertEquals("nosniff", r.header("x-content-type-options"))
            // keep-alive: a second request on the same TLS connection
            assertEquals(200, c.request("GET", "/app.js", host).status)
        }
    }

    @Test
    fun rejectsForeignHostHeaders() {
        connect().use { c -> assertEquals(421, c.request("GET", "/", "evil.example:${server.port}").status) }
    }

    @Test
    fun refusesTls12() {
        try {
            connect("TLSv1.2").use { }
            fail("TLS 1.2 must be refused")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun deniedNetworksAreDroppedBeforeHandshake() {
        allow.set(false)
        try {
            connect().use { it.request("GET", "/", host) }
            fail("connection should have been closed")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun loginFlowProtectsApi() {
        connect().use { c ->
            assertEquals(401, c.request("GET", "/api/status", host).status)
            // Missing Origin -> CSRF protection
            assertEquals(403, c.request("POST", "/api/login", host, mapOf("Content-Type" to "application/json"), "{\"code\":\"x\"}").status)
            val (headers, _) = json("")
            val wrong = c.request("POST", "/api/login", host, headers, "{\"code\":\"00000000\"}")
            assertEquals(401, wrong.status)
            assertTrue(wrong.text.contains("attemptsLeft"))
            val cookie = login(c)
            val status = c.request("GET", "/api/status", host, mapOf("Cookie" to cookie))
            assertEquals(200, status.status)
            assertTrue(status.text.contains("\"version\":\"test\""))
            assertEquals(204, c.request("POST", "/api/logout", host, mapOf("Origin" to "https://$host", "Cookie" to cookie), "").status)
            assertEquals(401, c.request("GET", "/api/status", host, mapOf("Cookie" to cookie)).status)
        }
    }

    @Test
    fun pairingTokenLogsIn() {
        val token = access.issuePairingToken()
        connect().use { c ->
            val (headers, body) = json("{\"token\":\"$token\"}")
            assertEquals(204, c.request("POST", "/api/pair", host, headers, body).status)
            assertEquals(401, c.request("POST", "/api/pair", host, headers, body).status)
        }
    }

    @Test
    fun streamsH264OverWebSocket() {
        val cookie = connect().use { login(it) }
        hub.publishConfig(VideoConfig("avc1.64001f", 1280, 720, byteArrayOf(1, 2, 3), generation = 7))
        connect().use { c ->
            c.send(
                "GET /ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Origin: https://$host\r\nCookie: $cookie\r\n\r\n",
            )
            val upgrade = c.readResponse(readBody = false)
            assertEquals(101, upgrade.status)
            assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", upgrade.header("sec-websocket-accept"))

            val config = readServerFrame(c)
            assertEquals(WebSocket.OP_TEXT, config.first)
            val text = String(config.second)
            assertTrue(text.contains("\"type\":\"config\"") && text.contains("avc1.64001f"))
            assertTrue(text.contains(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))

            // Give the server a moment to register the subscriber, then publish a keyframe.
            Thread.sleep(200)
            hub.publishFrame(EncodedFrame(byteArrayOf(9, 8, 7), 0x0102030405L, isKey = true, generation = 7))
            val frame = readServerFrame(c)
            assertEquals(WebSocket.OP_BINARY, frame.first)
            val data = frame.second
            assertEquals(13, data.size)
            assertEquals(1, data[0].toInt())
            assertEquals(1, data[1].toInt())
            var ts = 0L
            for (i in 2 until 10) ts = (ts shl 8) or (data[i].toLong() and 0xFF)
            assertEquals(0x0102030405L, ts)
            assertEquals(1, server.currentViewers().size)
        }
    }

    @Test
    fun webSocketRequiresSameOrigin() {
        val cookie = connect().use { login(it) }
        connect().use { c ->
            c.send(
                "GET /ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Origin: https://evil.example\r\nCookie: $cookie\r\n\r\n",
            )
            assertEquals(403, c.readResponse().status)
        }
    }

    @Test
    fun streamsMjpeg() {
        val cookie = connect().use { login(it) }
        connect().use { c ->
            c.send("GET /mjpeg HTTP/1.1\r\nHost: $host\r\nCookie: $cookie\r\n\r\n")
            val head = c.readResponse(readBody = false)
            assertEquals(200, head.status)
            assertTrue(head.header("content-type")!!.startsWith("multipart/x-mixed-replace"))
            Thread.sleep(200)
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
            hub.publishJpeg(jpeg)
            assertEquals("--awcframe", TestSupport.readLine(c.input))
            assertEquals("Content-Type: image/jpeg", TestSupport.readLine(c.input))
            assertEquals("Content-Length: 6", TestSupport.readLine(c.input))
            assertEquals("", TestSupport.readLine(c.input))
            val body = ByteArray(6)
            var n = 0
            while (n < 6) n += c.input.read(body, n, 6 - n)
            assertEquals(jpeg.toList(), body.toList())
        }
    }

    @Test
    fun snapshotReturnsOneFreshPictureToLoggedInClients() {
        val cookie = connect().use { login(it) }
        connect().use { c ->
            assertEquals(401, c.request("GET", "/api/snapshot", host).status)
            assertEquals(403, c.request("GET", "/api/snapshot", host, mapOf("Cookie" to cookie, "Sec-Fetch-Site" to "cross-site")).status)
            // Only a picture published after the request counts (subscribing starts JPEG encoding).
            hub.publishJpeg(byteArrayOf(1, 2, 3))
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 7, 0xFF.toByte(), 0xD9.toByte())
            val publishing = java.util.concurrent.atomic.AtomicBoolean(true)
            val publisher = Thread {
                while (publishing.get()) {
                    hub.publishJpeg(jpeg)
                    Thread.sleep(50)
                }
            }.apply { start() }
            val headers = mapOf("Cookie" to cookie, "User-Agent" to "AndroidWebCam-AI/1.0.0 (Linux)")
            val r = c.request("GET", "/api/snapshot", host, headers)
            publishing.set(false)
            publisher.join()
            assertEquals(200, r.status)
            assertEquals("image/jpeg", r.header("content-type"))
            assertEquals("no-store", r.header("cache-control"))
            assertEquals(jpeg.toList(), r.body.toList())
            assertEquals(listOf("127.0.0.1 Unknown"), snapshots) // client as described at login
            assertEquals(0, hub.mjpegViewers) // JPEG encoding stops again
            // At most one picture per second and address: not an unlimited extra stream.
            val again = c.request("GET", "/api/snapshot", host, headers)
            assertEquals(429, again.status)
            assertEquals("1", again.header("retry-after"))
        }
    }

    @Test
    fun snapshotWithoutPictureFailsCleanly() {
        val cookie = connect().use { login(it) }
        connect().use { c ->
            val r = c.request("GET", "/api/snapshot", host, mapOf("Cookie" to cookie))
            assertEquals(503, r.status)
            assertEquals(0, hub.mjpegViewers)
        }
    }

    @Test
    fun statusTellsLoggedInViewersTheKeyPinAndOffersTheAiBridge() {
        val cookie = connect().use { login(it) }
        connect().use { c ->
            val status = c.request("GET", "/api/status", host, mapOf("Cookie" to cookie))
            assertTrue(status.text.contains("\"pin\":\"${identity.publicKeyPin}\""))
            assertTrue(status.text.contains("\"fingerprint\":\"${identity.fingerprintSha256}\""))
            val bridge = c.request("GET", "/awc-ai.mjs", host)
            assertEquals(200, bridge.status)
            assertEquals("attachment; filename=\"awc-ai.mjs\"", bridge.header("content-disposition"))
            assertTrue(bridge.text.contains("camera_snapshot"))
        }
    }

    @Test
    fun limitsConcurrentViewers() {
        val cookie = connect().use { login(it) }
        val clients = (1..5).map { connect() }
        try {
            val statuses = clients.map { c ->
                c.send("GET /mjpeg HTTP/1.1\r\nHost: $host\r\nCookie: $cookie\r\n\r\n")
                c.readResponse(readBody = false).status
            }
            assertEquals(4, statuses.count { it == 200 })
            assertEquals(1, statuses.count { it == 503 })
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun slowHeadersAreCutOff() {
        connect().use { c ->
            c.send("GET / HTTP/1.1\r\nHost: $host\r\n")
            Thread.sleep(800)
            c.send("X-Slow: 1\r\n\r\n")
            assertEquals(408, c.readResponse().status)
        }
    }

    @Test
    fun oneDeviceCannotTakeAllConnectionSlots() {
        val clients = ArrayList<TestSupport.Client>()
        try {
            repeat(6) { clients.add(connect()) }
            try {
                connect().use { it.request("GET", "/", host) }
                fail("7th connection from the same address must be refused")
            } catch (expected: Exception) {
            }
        } finally {
            clients.forEach { it.close() }
        }
        Thread.sleep(300)
        connect().use { assertEquals(200, it.request("GET", "/", host).status) }
    }

    /** Reads one unmasked server frame: (opcode, payload). */
    private fun readServerFrame(c: TestSupport.Client): Pair<Int, ByteArray> {
        val input = c.input
        val b0 = input.read()
        val b1 = input.read()
        assertNotNull(b0)
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) read += input.read(buf, read, buf.size - read)
        out.write(buf)
        return (b0 and 0x0F) to out.toByteArray()
    }
}
