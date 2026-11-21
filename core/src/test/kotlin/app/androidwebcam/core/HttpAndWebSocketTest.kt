package app.androidwebcam.core

import app.androidwebcam.core.http.HttpException
import app.androidwebcam.core.http.HttpParser
import app.androidwebcam.core.http.WebSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpAndWebSocketTest {
    private fun parse(raw: String) = HttpParser.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun expectStatus(raw: String, status: Int) {
        try {
            parse(raw)
            fail("expected HTTP $status")
        } catch (e: HttpException) {
            assertEquals(status, e.status)
        }
    }

    @Test
    fun parsesSimpleRequests() {
        val r = parse("GET /app.js?v=1 HTTP/1.1\r\nHost: 192.168.1.2:8443\r\nCookie: a=1\r\nCookie: __Host-awc_sid=xyz\r\n\r\n")!!
        assertEquals("GET", r.method)
        assertEquals("/app.js", r.path)
        assertEquals("192.168.1.2:8443", r.header("HOST"))
        assertEquals("xyz", r.cookie("__Host-awc_sid"))
        assertTrue(r.keepAlive)
        val post = parse("POST /api/login HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\nConnection: close\r\n\r\nabcd")!!
        assertEquals("abcd", String(post.body))
        assertTrue(!post.keepAlive)
        assertNull(parse(""))
    }

    @Test
    fun rejectsMalformedOrDangerousRequests() {
        expectStatus("GET / HTTP/1.1\nHost: h\n\n", 400) // bare LF
        expectStatus("GET  / HTTP/1.1\r\n\r\n", 400)
        expectStatus("get / HTTP/1.1\r\n\r\n", 400)
        expectStatus("GET http://evil/ HTTP/1.1\r\n\r\n", 400)
        expectStatus("GET / HTTP/2.0\r\n\r\n", 505)
        expectStatus("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", 400)
        expectStatus("POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n", 400)
        expectStatus("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", 501)
        expectStatus("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n", 400)
        expectStatus("POST / HTTP/1.1\r\nContent-Length: 99999\r\n\r\n", 413)
        expectStatus("GET / HTTP/1.1\r\nX: a\r\n b\r\n\r\n", 400) // obsolete folding
        expectStatus("GET / HTTP/1.1\r\nBad Header: x\r\n\r\n", 400)
        expectStatus("GET /" + "a".repeat(3000) + " HTTP/1.1\r\n\r\n", 414)
        val many = StringBuilder("GET / HTTP/1.1\r\n")
        repeat(60) { many.append("X-$it: y\r\n") }
        expectStatus(many.append("\r\n").toString(), 431)
    }

    @Test
    fun webSocketAcceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun readsMaskedClientFramesAndRejectsUnmasked() {
        val payload = "{\"type\":\"keyframe\"}".toByteArray()
        val mask = byteArrayOf(1, 2, 3, 4)
        val frame = ByteArrayOutputStream()
        frame.write(0x81)
        frame.write(0x80 or payload.size)
        frame.write(mask)
        payload.forEachIndexed { i, b -> frame.write(b.toInt() xor mask[i % 4].toInt()) }
        val parsed = WebSocket.readFrame(ByteArrayInputStream(frame.toByteArray()), 1024)
        assertEquals(WebSocket.OP_TEXT, parsed.opcode)
        assertArrayEquals(payload, parsed.payload)

        val unmasked = byteArrayOf(0x81.toByte(), 0x01, 0x41)
        try {
            WebSocket.readFrame(ByteArrayInputStream(unmasked), 1024)
            fail("unmasked client frame must be rejected")
        } catch (e: WebSocket.ProtocolException) {
            assertEquals(1002, e.closeCode)
        }
    }

    @Test
    fun writesServerFramesWithCorrectLengthEncoding() {
        for (size in listOf(0, 125, 126, 65535, 65536, 200_000)) {
            val out = ByteArrayOutputStream()
            WebSocket.writeFrame(out, WebSocket.OP_BINARY, ByteArray(size), prefix = ByteArray(10))
            val bytes = out.toByteArray()
            val total = size + 10
            val headerSize = when {
                total < 126 -> 2
                total < 65536 -> 4
                else -> 10
            }
            assertEquals(headerSize + total, bytes.size)
            assertEquals(0x82.toByte(), bytes[0])
        }
    }
}
