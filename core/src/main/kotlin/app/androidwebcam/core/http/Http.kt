package app.androidwebcam.core.http

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

class HttpException(val status: Int, message: String) : Exception(message)

class HttpRequest(
    val method: String,
    /** Request path without query string. */
    val path: String,
    val version: String,
    /** Header names are lower-case. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val keepAlive: Boolean
        get() {
            val connection = header("connection")?.lowercase() ?: ""
            return if (version == "HTTP/1.1") !connection.contains("close") else connection.contains("keep-alive")
        }

    fun cookie(name: String): String? {
        val raw = header("cookie") ?: return null
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            if (part.substring(0, idx).trim() == name) return part.substring(idx + 1).trim()
        }
        return null
    }
}

/**
 * Strict HTTP/1.1 request parser with hard limits. Anything unusual is rejected
 * instead of being interpreted leniently (defense against smuggling/parser bugs).
 */
object HttpParser {
    const val MAX_REQUEST_LINE = 2048
    const val MAX_HEADER_LINE = 4096
    const val MAX_HEADERS = 48
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY = 2048

    private val METHOD = Regex("^[A-Z]{1,16}$")
    private val TOKEN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val SINGLETON_HEADERS = setOf("host", "content-length", "content-type", "origin", "upgrade",
        "sec-websocket-key", "sec-websocket-version", "authorization")

    /**
     * Returns null on a clean EOF before a new request starts. The complete header block must
     * arrive within [maxHeaderMillis] of its first byte (defense against slow-header attacks).
     */
    fun read(input: InputStream, maxHeaderMillis: Long = Long.MAX_VALUE / 2_000_000): HttpRequest? {
        val clock = Deadline(maxHeaderMillis)
        val requestLine = readLine(input, MAX_REQUEST_LINE, allowEof = true, clock) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpException(400, "malformed request line")
        val (method, target, version) = parts
        if (!METHOD.matches(method)) throw HttpException(400, "bad method")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpException(505, "unsupported version")
        if (!target.startsWith("/") || target.length > 1024) throw HttpException(400, "bad target")
        if (target.any { it.code < 0x21 || it.code > 0x7E }) throw HttpException(400, "bad target characters")
        val path = target.substringBefore('?').substringBefore('#')

        val headers = LinkedHashMap<String, String>()
        var total = 0
        var count = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE, allowEof = false, clock)!!
            if (line.isEmpty()) break
            total += line.length
            if (++count > MAX_HEADERS || total > MAX_HEADER_BYTES) throw HttpException(431, "headers too large")
            if (line[0] == ' ' || line[0] == '\t') throw HttpException(400, "obsolete line folding")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpException(400, "malformed header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpException(400, "bad header name")
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7F }) throw HttpException(400, "bad header value")
            val key = name.lowercase()
            val existing = headers[key]
            headers[key] = when {
                existing == null -> value
                key in SINGLETON_HEADERS -> throw HttpException(400, "duplicate $key")
                key == "cookie" -> "$existing; $value"
                else -> "$existing, $value"
            }
        }
        if (headers.containsKey("transfer-encoding")) throw HttpException(501, "transfer-encoding not supported")
        val lengthHeader = headers["content-length"]
        val length = when {
            lengthHeader == null -> 0
            lengthHeader.isEmpty() || lengthHeader.length > 6 || !lengthHeader.all { it in '0'..'9' } ->
                throw HttpException(400, "bad content-length")
            else -> lengthHeader.toInt()
        }
        if (length > MAX_BODY) throw HttpException(413, "body too large")
        val body = ByteArray(length)
        readFully(input, body)
        return HttpRequest(method, path, version, headers, body)
    }

    /** Starts counting at the first received byte. */
    private class Deadline(private val maxMillis: Long) {
        private var startedAt = 0L
        fun tick() {
            val now = System.nanoTime()
            if (startedAt == 0L) startedAt = now
            else if ((now - startedAt) / 1_000_000 > maxMillis) throw HttpException(408, "request header timeout")
        }
    }

    private fun readLine(input: InputStream, max: Int, allowEof: Boolean, clock: Deadline): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (allowEof && buf.size() == 0) return null
                throw EOFException("connection closed mid-request")
            }
            clock.tick()
            if (b == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpException(400, "bare CR")
                return String(buf.toByteArray(), Charsets.ISO_8859_1)
            }
            if (b == '\n'.code) throw HttpException(400, "bare LF")
            if (buf.size() >= max) throw HttpException(if (max == MAX_REQUEST_LINE) 414 else 431, "line too long")
            buf.write(b)
        }
    }

    fun readFully(input: InputStream, target: ByteArray, offset: Int = 0, length: Int = target.size - offset) {
        var read = 0
        while (read < length) {
            val n = input.read(target, offset + read, length - read)
            if (n < 0) throw EOFException("unexpected end of stream")
            read += n
        }
    }
}

object HttpStatus {
    fun reason(code: Int): String = when (code) {
        101 -> "Switching Protocols"
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Content Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }
}

class HttpResponse(val status: Int) {
    val headers = ArrayList<Pair<String, String>>()
    var body: ByteArray = ByteArray(0)

    fun header(name: String, value: String): HttpResponse {
        require(!value.contains('\r') && !value.contains('\n')) { "header injection" }
        headers.add(name to value)
        return this
    }

    fun body(bytes: ByteArray, contentType: String): HttpResponse {
        body = bytes
        header("Content-Type", contentType)
        return this
    }

    fun text(text: String, contentType: String = "text/plain; charset=utf-8") = body(text.toByteArray(Charsets.UTF_8), contentType)
    fun json(json: String) = body(json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    /** Writes status line, headers and body. Content-Length is always explicit. */
    fun writeTo(out: OutputStream, keepAlive: Boolean, includeBody: Boolean = true) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (status != 101) {
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (includeBody && body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
