package app.androidwebcam.core.tls

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager

/** TLS policy: TLS 1.3 only (AEAD ciphers with forward secrecy), no client certificates. */
object Tls {
    const val PROTOCOL = "TLSv1.3"

    fun serverContext(keyManager: IdentityKeyManager, random: SecureRandom = SecureRandom()): SSLContext =
        SSLContext.getInstance(PROTOCOL).apply { init(arrayOf(keyManager), null, random) }

    fun hardenServerSocket(socket: SSLServerSocket) {
        socket.enabledProtocols = arrayOf(PROTOCOL)
        socket.needClientAuth = false
        socket.wantClientAuth = false
    }

    /** Trust manager that accepts exactly one pinned certificate (used by self-tests). */
    fun pinnedTrustManager(pinned: X509Certificate): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || !chain[0].encoded.contentEquals(pinned.encoded)) {
                throw CertificateException("certificate does not match pinned identity")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
    }

    /**
     * Performs a complete in-memory TLS 1.3 handshake against [serverContext] to prove that
     * the identity (including a hardware-backed key) really works with this platform's TLS
     * stack before the server goes live. Throws on failure.
     */
    fun selfTest(serverContext: SSLContext, certificate: X509Certificate) {
        val server = serverContext.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf(PROTOCOL)
        }
        val clientContext = SSLContext.getInstance(PROTOCOL).apply {
            init(null, arrayOf(pinnedTrustManager(certificate)), SecureRandom())
        }
        val client = clientContext.createSSLEngine("awc-self-test", 1).apply {
            useClientMode = true
            enabledProtocols = arrayOf(PROTOCOL)
        }
        handshake(client, server)
        val clientCert = client.session.peerCertificates.firstOrNull() as? X509Certificate
        if (clientCert == null || !clientCert.encoded.contentEquals(certificate.encoded)) {
            throw SSLException("self-test negotiated an unexpected certificate")
        }
        if (client.session.protocol != PROTOCOL) throw SSLException("self-test negotiated ${client.session.protocol}")
    }

    private fun handshake(client: SSLEngine, server: SSLEngine) {
        val empty = ByteBuffer.allocate(0)
        val clientToServer = ByteBuffer.allocate(maxOf(client.session.packetBufferSize, 64 * 1024))
        val serverToClient = ByteBuffer.allocate(maxOf(server.session.packetBufferSize, 64 * 1024))
        val clientApp = ByteBuffer.allocate(maxOf(client.session.applicationBufferSize, 64 * 1024))
        val serverApp = ByteBuffer.allocate(maxOf(server.session.applicationBufferSize, 64 * 1024))
        client.beginHandshake()
        server.beginHandshake()
        repeat(1000) {
            val c = client.handshakeStatus
            val s = server.handshakeStatus
            if (isDone(c) && isDone(s)) return
            step(client, c, empty, clientToServer, serverToClient, clientApp)
            step(server, s, empty, serverToClient, clientToServer, serverApp)
        }
        throw SSLException("TLS self-test handshake did not complete")
    }

    private fun isDone(status: HandshakeStatus) =
        status == HandshakeStatus.NOT_HANDSHAKING || status == HandshakeStatus.FINISHED

    private fun step(engine: SSLEngine, status: HandshakeStatus, empty: ByteBuffer, out: ByteBuffer, input: ByteBuffer, app: ByteBuffer) {
        when (status) {
            HandshakeStatus.NEED_WRAP -> engine.wrap(empty, out)
            HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                input.flip()
                engine.unwrap(input, app)
                input.compact()
                app.clear()
            }
            HandshakeStatus.NEED_TASK -> {
                while (true) {
                    val task = engine.delegatedTask ?: break
                    task.run()
                }
            }
            else -> Unit
        }
    }
}
