package app.androidwebcam

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamState
import app.androidwebcam.service.StreamingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * End-to-end test on a real Android system (CI runs it on an Android 11 / API 30 emulator):
 * service + Camera2 + GL + MediaCodec + Keystore-backed TLS 1.3 server, accessed over the
 * device's own LAN address exactly like a browser would.
 */
@RunWith(AndroidJUnit4::class)
class StreamingDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.CAMERA)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Timeouts are multiplied by this (software-emulated devices are much slower). */
    private val scale = InstrumentationRegistry.getArguments().getString("timeoutScale")?.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0

    private fun t(ms: Long): Long = (ms * scale).toLong()

    @After
    fun tearDown() {
        StreamingService.stop(context)
        waitFor(t(20_000)) { StreamController.state.value.takeIf { it.phase == Phase.IDLE } }
    }

    @Test
    fun streamsOverTls13OnTheLocalNetwork() {
        SettingsStore(context).save(StreamSettings(quality = Quality.P720, fps = 15, orientation = 0, port = 8443))
        // Start the service while the activity is in the foreground (Android 11 camera rules).
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = launchMainActivity(t(60_000))
        val activity = main
        try {
            instrumentation.runOnMainSync {
                main.autoBlackoutDelayMs = t(3_000) // instead of 1 minute without touch
                StreamingService.start(activity)
            }
            val state = waitFor(t(90_000)) {
                StreamController.state.value.takeIf { it.phase == Phase.RUNNING && it.endpoints.isNotEmpty() && it.streamWidth > 0 }
            }
            log("state: endpoints=${state.endpoints} stream=${state.streamWidth}x${state.streamHeight} hardwareKey=${state.hardwareKey}")
            assertTrue("TLS key must live in the Android Keystore", state.hardwareKey)
            assertTrue("portrait stream expected for orientation 0", state.streamHeight > state.streamWidth)

            // Keep-awake: the screen never times out while streaming, and after the idle delay the
            // app switches to the black standby screen at the lowest brightness. Everything below
            // runs while the screen is black, proving that streaming continues in standby.
            // (applied by the UI after it has seen the new state, i.e. a frame or two later)
            waitUntil(t(10_000), "screen kept on while streaming") {
                main.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            }
            waitUntil(t(30_000), "automatic black-screen standby") { main.blackout }
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF, brightness(activity), 0f)
            log("keep-awake: screen on, black standby at lowest brightness")

            val host = state.endpoints.first().address.hostAddress!!
            val authority = "$host:${state.port}"
            val tls = pinnedContext(state.fingerprint!!)

            connect(tls, host, state.port).use { c ->
                val page = c.request("GET", "/", authority)
                assertEquals(200, page.status)
                assertTrue(page.text.contains("app.js"))
                assertTrue(page.header("content-security-policy")!!.contains("default-src 'none'"))
                assertEquals(421, c.request("GET", "/", "evil.example:${state.port}").status)
            }

            val cookie = connect(tls, host, state.port).use { c ->
                val wrong = c.request("POST", "/api/login", authority, json(authority), "{\"code\":\"0000-0000\"}")
                assertEquals(401, wrong.status)
                val ok = c.request("POST", "/api/login", authority, json(authority), "{\"code\":\"${state.accessCode}\"}")
                assertEquals(204, ok.status)
                ok.header("set-cookie")!!.substringBefore(';')
            }

            // H.264 over WebSocket: decoder config + a keyframe that Android's own decoder accepts.
            connect(tls, host, state.port).use { c ->
                c.send(
                    "GET /ws HTTP/1.1\r\nHost: $authority\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Origin: https://$authority\r\nCookie: $cookie\r\n\r\n",
                )
                assertEquals(101, c.readHead().status)
                var config: String? = null
                var keyframe: ByteArray? = null
                val deadline = SystemClock.uptimeMillis() + t(60_000)
                while ((config == null || keyframe == null) && SystemClock.uptimeMillis() < deadline) {
                    val (opcode, payload) = c.readWsFrame()
                    if (opcode == 1) config = String(payload)
                    if (opcode == 2 && payload[1].toInt() and 1 == 1 && config != null) keyframe = payload
                }
                assertTrue("no decoder config received", config != null)
                assertTrue("no keyframe received", keyframe != null)
                log("config: $config, keyframe ${keyframe!!.size} bytes")
                val width = Regex("\"width\":(\\d+)").find(config!!)!!.groupValues[1].toInt()
                val height = Regex("\"height\":(\\d+)").find(config)!!.groupValues[1].toInt()
                assertEquals(state.streamWidth, width)
                assertEquals(state.streamHeight, height)
                val description = Base64.getDecoder().decode(Regex("\"description\":\"([^\"]+)\"").find(config)!!.groupValues[1])
                assertTrue("Android decoder must accept the stream", decodesKeyframe(description, keyframe.copyOfRange(10, keyframe.size), width, height))
            }

            // MJPEG compatibility stream: a real JPEG in the same (portrait) orientation.
            connect(tls, host, state.port).use { c ->
                c.send("GET /mjpeg HTTP/1.1\r\nHost: $authority\r\nCookie: $cookie\r\n\r\n")
                assertEquals(200, c.readHead().status)
                assertEquals("--awcframe", c.readLine())
                assertEquals("Content-Type: image/jpeg", c.readLine())
                val length = c.readLine().substringAfter(": ").toInt()
                assertEquals("", c.readLine())
                val jpeg = c.readExactly(length)
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                log("mjpeg ${bitmap.width}x${bitmap.height}")
                assertTrue("MJPEG must be portrait like the H.264 stream", bitmap.height > bitmap.width)
            }

            // Single picture for AI tools on a computer: logged-in clients only, a real portrait JPEG,
            // and the phone lists it. The key pin offered to the AI tool matches the TLS key.
            connect(tls, host, state.port).use { c ->
                assertEquals(401, c.request("GET", "/api/snapshot", authority).status)
                val r = c.request("GET", "/api/snapshot", authority, mapOf("Cookie" to cookie, "User-Agent" to "AndroidWebCam-AI/1.0.0 (Linux)"))
                assertEquals(200, r.status)
                assertEquals("image/jpeg", r.header("content-type"))
                val bitmap = BitmapFactory.decodeByteArray(r.body, 0, r.body.size)
                log("snapshot ${bitmap.width}x${bitmap.height}")
                assertTrue("snapshot must be portrait like the stream", bitmap.height > bitmap.width)
                val last = StreamController.state.value.lastSnapshot
                assertEquals(host, last?.address)
                assertTrue(StreamController.state.value.snapshotCount >= 1)

                val certificate = c.socket.session.peerCertificates[0] as X509Certificate
                val spki = ServerIdentity.subjectPublicKeyInfo(certificate.encoded)
                val pin = "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
                val status = c.request("GET", "/api/status", authority, mapOf("Cookie" to cookie))
                assertTrue("status must offer the key pin $pin: ${status.text}", status.text.contains("\"pin\":\"$pin\""))
                assertEquals(200, c.request("GET", "/awc-ai.mjs", authority).status)

                // AI access key for agents: created by the logged-in viewer, then a picture with only
                // the key (what the curl command in the agent instructions does); revocable on the phone.
                val json = mapOf("Cookie" to cookie, "Origin" to "https://$authority", "Content-Type" to "application/json")
                val created = c.request("POST", "/api/ai-key", authority, json, "{}")
                assertEquals(200, created.status)
                val key = Regex("\"key\":\"(awc_[A-Za-z0-9_-]{43})\"").find(created.text)!!.groupValues[1]
                Thread.sleep(1_100) // at most one picture per second and address
                val viaKey = c.request("GET", "/api/snapshot", authority, mapOf("Authorization" to "Bearer $key", "User-Agent" to "curl/8.5.0"))
                assertEquals(200, viaKey.status)
                val picture = BitmapFactory.decodeByteArray(viaKey.body, 0, viaKey.body.size)
                assertTrue(picture.height > picture.width)
                assertEquals("AI key · curl", StreamController.state.value.lastSnapshot?.client)
                assertTrue(StreamController.state.value.aiKeys.any { it.address == host })
                StreamController.revokeAiKeys()
                assertTrue(StreamController.state.value.aiKeys.isEmpty())
                assertEquals(401, c.request("GET", "/api/snapshot", authority, mapOf("Authorization" to "Bearer $key")).status)
                log("AI key: picture ${picture.width}x${picture.height}, revoked")
            }

            assertTrue(StreamController.state.value.viewers.isEmpty() || StreamController.state.value.phase == Phase.RUNNING)

            // Leaving the standby restores the normal brightness; the screen stays on while streaming.
            instrumentation.runOnMainSync { main.exitBlackout() }
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(activity), 0f)
            assertTrue(windowFlags(activity) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            // Loopback (other apps on the phone) is refused before the TLS handshake.
            try {
                connect(tls, "127.0.0.1", state.port).use { it.request("GET", "/", "127.0.0.1:${state.port}") }
                fail("loopback connections must be refused")
            } catch (expected: Exception) {
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /**
     * Tapping "Stop" while viewers are watching (H.264 and MJPEG) and pictures are being taken must
     * stop cleanly: no crash, the activity stays usable and shows the idle state.
     */
    @Test
    fun stopWhileViewersWatchDoesNotCrash() {
        SettingsStore(context).save(StreamSettings(quality = Quality.P720, fps = 15, orientation = 0, port = 8443))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = launchMainActivity(t(60_000))
        val workers = ArrayList<Thread>()
        val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()
        try {
            instrumentation.runOnMainSync { StreamingService.start(main) }
            val state = waitFor(t(90_000)) {
                StreamController.state.value.takeIf { it.phase == Phase.RUNNING && it.endpoints.isNotEmpty() && it.streamWidth > 0 }
            }
            val host = state.endpoints.first().address.hostAddress!!
            val authority = "$host:${state.port}"
            val tls = pinnedContext(state.fingerprint!!)
            val cookie = connect(tls, host, state.port).use { c ->
                c.request("POST", "/api/login", authority, json(authority), "{\"code\":\"${state.accessCode}\"}").header("set-cookie")!!.substringBefore(';')
            }
            val ws = connect(tls, host, state.port).also(clients::add)
            ws.send(
                "GET /ws HTTP/1.1\r\nHost: $authority\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Origin: https://$authority\r\nCookie: $cookie\r\n\r\n",
            )
            assertEquals(101, ws.readHead().status)
            val mjpeg = connect(tls, host, state.port).also(clients::add)
            mjpeg.send("GET /mjpeg HTTP/1.1\r\nHost: $authority\r\nCookie: $cookie\r\n\r\n")
            assertEquals(200, mjpeg.readHead().status)
            // Keep both viewers reading, and keep taking single pictures, until the server goes away.
            workers += Thread { try { while (true) ws.readWsFrame() } catch (_: Throwable) {} }
            workers += Thread { try { while (true) mjpeg.readLine() } catch (_: Throwable) {} }
            workers += Thread {
                try {
                    while (true) {
                        connect(tls, host, state.port).also(clients::add).use { it.request("GET", "/api/snapshot", authority, mapOf("Cookie" to cookie)) }
                        Thread.sleep(1_000)
                    }
                } catch (_: Throwable) {
                }
            }
            workers.forEach { it.isDaemon = true; it.start() }
            waitFor(t(30_000)) { StreamController.state.value.takeIf { it.viewers.size >= 2 } }
            Thread.sleep(t(3_000))
            log("stopping with ${StreamController.state.value.viewers.size} viewers")

            instrumentation.runOnMainSync { StreamingService.stop(main) } // what the Stop button does
            waitFor(t(30_000)) { StreamController.state.value.takeIf { it.phase == Phase.IDLE } }
            Thread.sleep(t(5_000)) // late callbacks (server threads, GL thread, notifications)
            var alive = false
            instrumentation.runOnMainSync { alive = !main.isDestroyed && !main.isFinishing }
            assertTrue("activity must survive stopping", alive)
            assertEquals(Phase.IDLE, StreamController.state.value.phase)
            log("stopped cleanly with viewers connected")
        } finally {
            clients.forEach { runCatching { it.close() } }
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun log(message: String) = println("AWC-TEST $message")

    private fun windowFlags(activity: android.app.Activity): Int {
        var flags = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { flags = activity.window.attributes.flags }
        return flags
    }

    private fun brightness(activity: android.app.Activity): Float {
        var value = 0f
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = activity.window.attributes.screenBrightness }
        return value
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        fail("timeout waiting for $what")
    }

    private fun json(authority: String) = mapOf("Origin" to "https://$authority", "Content-Type" to "application/json")

    private fun waitFor(timeoutMs: Long, probe: () -> StreamState?): StreamState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = StreamController.state.value
            if (s.phase == Phase.ERROR) fail("streaming failed: ${s.error} ${s.errorDetail}")
            probe()?.let { return it }
            Thread.sleep(100)
        }
        fail("timeout, last state: ${StreamController.state.value}")
        throw AssertionError()
    }

    /** Trusts exactly the certificate whose SHA-256 fingerprint the phone displays. */
    private fun pinnedContext(fingerprint: String): SSLContext {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString(":") { String.format("%02X", it) }
                if (actual != fingerprint) throw CertificateException("fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), SecureRandom()) }
    }

    private fun connect(tls: SSLContext, host: String, port: Int): Client {
        val socket = tls.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), t(10_000).toInt())
        socket.soTimeout = t(30_000).toInt()
        socket.startHandshake()
        assertEquals("TLSv1.3", socket.session.protocol)
        return Client(socket)
    }

    private fun decodesKeyframe(avcC: ByteArray, avcc: ByteArray, width: Int, height: Int): Boolean {
        // avcC -> SPS/PPS with start codes for csd-0/csd-1; AVCC frame -> Annex B.
        val spsLen = ((avcC[6].toInt() and 0xFF) shl 8) or (avcC[7].toInt() and 0xFF)
        val sps = avcC.copyOfRange(8, 8 + spsLen)
        val ppsOffset = 8 + spsLen + 1
        val ppsLen = ((avcC[ppsOffset].toInt() and 0xFF) shl 8) or (avcC[ppsOffset + 1].toInt() and 0xFF)
        val pps = avcC.copyOfRange(ppsOffset + 2, ppsOffset + 2 + ppsLen)
        val startCode = byteArrayOf(0, 0, 0, 1)
        val annexB = ByteArrayOutputStream()
        var i = 0
        while (i + 4 <= avcc.size) {
            val n = ((avcc[i].toInt() and 0xFF) shl 24) or ((avcc[i + 1].toInt() and 0xFF) shl 16) or
                ((avcc[i + 2].toInt() and 0xFF) shl 8) or (avcc[i + 3].toInt() and 0xFF)
            annexB.write(startCode)
            annexB.write(avcc, i + 4, n)
            i += 4 + n
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            val frame = annexB.toByteArray()
            val inIndex = decoder.dequeueInputBuffer(t(5_000) * 1000)
            if (inIndex < 0) return false
            decoder.getInputBuffer(inIndex)!!.put(frame)
            decoder.queueInputBuffer(inIndex, 0, frame.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + t(10_000)
            while (SystemClock.uptimeMillis() < deadline) {
                val out = decoder.dequeueOutputBuffer(info, 100_000)
                if (out >= 0) {
                    decoder.releaseOutputBuffer(out, false)
                    if (info.size > 0 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
            return false
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            decoder.release()
        }
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String) = headers[name.lowercase()]
        val text get() = String(body)
    }

    private class Client(val socket: SSLSocket) : AutoCloseable {
        private val input: InputStream = BufferedInputStream(socket.inputStream)
        private val output = socket.outputStream

        fun send(raw: String) {
            output.write(raw.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        fun request(method: String, path: String, host: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response {
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            sb.append("\r\n")
            if (body != null) sb.append(body)
            send(sb.toString())
            val head = readHead()
            val length = head.header("content-length")?.toInt() ?: 0
            return Response(head.status, head.headers, readExactly(length))
        }

        fun readHead(): Response {
            val status = readLine().split(' ')[1].toInt()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                headers.putIfAbsent(line.substring(0, idx).trim().lowercase(), line.substring(idx + 1).trim())
            }
            return Response(status, headers, ByteArray(0))
        }

        fun readLine(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                check(b >= 0) { "connection closed" }
                if (b == '\r'.code) {
                    input.read()
                    return String(out.toByteArray(), Charsets.ISO_8859_1)
                }
                out.write(b)
            }
        }

        fun readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                check(r >= 0) { "connection closed" }
                read += r
            }
            return buf
        }

        fun readWsFrame(): Pair<Int, ByteArray> {
            val b0 = input.read()
            val b1 = input.read()
            check(b0 >= 0 && b1 >= 0) { "connection closed" }
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            return (b0 and 0x0F) to readExactly(len.toInt())
        }

        override fun close() = socket.close()
    }
}
