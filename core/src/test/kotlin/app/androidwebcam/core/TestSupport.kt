package app.androidwebcam.core

import app.androidwebcam.core.tls.SelfSignedCertificate
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.core.tls.Tls
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object TestSupport {
    val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    fun softwareIdentity(ips: List<InetAddress> = listOf(loopback)): ServerIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val cert = SelfSignedCertificate.create(kp.public, SelfSignedCertificate.softwareSigner(kp.private), ips)
        return ServerIdentity(kp.private, cert, hardwareBacked = false)
    }

    fun clientContext(identity: ServerIdentity, protocol: String = "TLSv1.3"): SSLContext =
        SSLContext.getInstance(protocol).apply {
            init(null, arrayOf(Tls.pinnedTrustManager(identity.certificate)), SecureRandom())
        }

    class Response(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Minimal HTTP/1.1 client over an already-connected TLS socket. */
    class Client(val socket: SSLSocket) : AutoCloseable {
        val input = BufferedInputStream(socket.inputStream)
        val output = socket.outputStream

        fun send(raw: String) {
            output.write(raw.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        fun request(method: String, path: String, host: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response {
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            sb.append("\r\n")
            if (body != null) sb.append(body)
            send(sb.toString())
            return readResponse()
        }

        fun readResponse(readBody: Boolean = true): Response {
            val statusLine = readLine(input)
            val status = statusLine.split(' ')[1].toInt()
            val headers = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                headers.getOrPut(line.substring(0, idx).trim().lowercase()) { ArrayList() }.add(line.substring(idx + 1).trim())
            }
            val length = headers["content-length"]?.first()?.toInt() ?: 0
            val body = ByteArray(if (readBody) length else 0)
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            return Response(status, headers, body)
        }

        override fun close() = socket.close()
    }

    fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            require(b >= 0) { "EOF" }
            if (b == '\r'.code) {
                input.read()
                return String(out.toByteArray(), Charsets.ISO_8859_1)
            }
            out.write(b)
        }
    }
}
