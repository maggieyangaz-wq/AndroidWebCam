package app.androidwebcam.core.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** RFC 6455 server-side helpers (no extensions, no fragmentation from clients). */
object WebSocket {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    class ProtocolException(val closeCode: Int, message: String) : IOException(message)

    fun acceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
    }

    /** Validates the upgrade request and returns the Sec-WebSocket-Accept value, or null. */
    fun validateUpgrade(request: HttpRequest): String? {
        if (request.method != "GET" || request.version != "HTTP/1.1") return null
        if (request.header("upgrade")?.equals("websocket", ignoreCase = true) != true) return null
        val connection = request.header("connection")?.lowercase() ?: return null
        if (connection.split(',').none { it.trim() == "upgrade" }) return null
        if (request.header("sec-websocket-version") != "13") return null
        val key = request.header("sec-websocket-key") ?: return null
        val decoded = try { Base64.getDecoder().decode(key) } catch (e: IllegalArgumentException) { return null }
        if (decoded.size != 16) return null
        return acceptKey(key)
    }

    /** Writes one unmasked (server-to-client) frame. Caller must serialize writers. */
    fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, prefix: ByteArray? = null) {
        val length = payload.size.toLong() + (prefix?.size ?: 0)
        val header = when {
            length < 126 -> byteArrayOf((0x80 or opcode).toByte(), length.toByte())
            length < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (length ushr 8).toByte(), length.toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte()
                it[1] = 127
                for (i in 0 until 8) it[2 + i] = (length ushr (56 - 8 * i)).toByte()
            }
        }
        out.write(header)
        if (prefix != null) out.write(prefix)
        out.write(payload)
    }

    fun closePayload(code: Int, reason: String): ByteArray {
        val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 120) it.copyOf(120) else it }
        return byteArrayOf((code ushr 8).toByte(), code.toByte()) + r
    }

    /** Reads one client frame; clients must mask, frames are size-limited. */
    fun readFrame(input: InputStream, maxPayload: Int): Frame {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw java.io.EOFException("websocket closed")
        val fin = b0 and 0x80 != 0
        if (b0 and 0x70 != 0) throw ProtocolException(1002, "reserved bits set")
        val opcode = b0 and 0x0F
        if (b1 and 0x80 == 0) throw ProtocolException(1002, "client frames must be masked")
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        val control = opcode and 0x8 != 0
        if (control && (length > 125 || !fin)) throw ProtocolException(1002, "bad control frame")
        if (length < 0 || length > maxPayload) throw ProtocolException(1009, "frame too large")
        val mask = ByteArray(4)
        HttpParser.readFully(input, mask)
        val payload = ByteArray(length.toInt())
        HttpParser.readFully(input, payload)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw java.io.EOFException("websocket closed")
        return b
    }
}
