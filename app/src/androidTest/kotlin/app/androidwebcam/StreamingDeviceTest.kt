package app.androidwebcam

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
        // startActivitySync has no fixed launch timeout (slow, software-emulated devices).
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.runOnMainSync { StreamingService.start(activity) }
            val state = waitFor(t(90_000)) {
                StreamController.state.value.takeIf { it.phase == Phase.RUNNING && it.endpoints.isNotEmpty() && it.streamWidth > 0 }
            }
            log("state: endpoints=${state.endpoints} stream=${state.streamWidth}x${state.streamHeight} hardwareKey=${state.hardwareKey}")
            assertTrue("TLS key must live in the Android Keystore", state.hardwareKey)
            assertTrue("portrait stream expected for orientation 0", state.streamHeight > state.streamWidth)

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

            assertTrue(StreamController.state.value.viewers.isEmpty() || StreamController.state.value.phase == Phase.RUNNING)

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

    // ------------------------------------------------------------------ helpers

    private fun log(message: String) = println("AWC-TEST $message")

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
