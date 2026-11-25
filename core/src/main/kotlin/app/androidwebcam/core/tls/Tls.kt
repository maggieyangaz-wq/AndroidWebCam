package app.androidwebcam.core.tls

import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** TLS policy: TLS 1.3 only (AEAD ciphers with forward secrecy), no client certificates. */
object Tls {
    const val PROTOCOL = "TLSv1.3"

    /**
     * The TLS implementation; null = the platform's. Android 6-9 have no TLS 1.3 built in, so there
     * the app sets its bundled copy of Conscrypt before the first connection.
     */
    @Volatile
    var provider: Provider? = null

    /** An uninitialized context of [protocol] from [provider]. */
    fun context(protocol: String = PROTOCOL): SSLContext =
        provider?.let { SSLContext.getInstance(protocol, it) } ?: SSLContext.getInstance(protocol)

    fun serverContext(keyManager: IdentityKeyManager, random: SecureRandom = SecureRandom()): SSLContext =
        context().apply { init(arrayOf(keyManager), null, random) }

    /**
     * Standard (PKIX) certificate validation trusting exactly [anchors]: used for the relay on old
     * Android versions, whose built-in root certificates are outdated.
     */
    fun trustManager(anchors: Collection<X509Certificate>): X509TrustManager {
        require(anchors.isNotEmpty()) { "no trust anchors" }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.forEachIndexed { i, certificate -> store.setCertificateEntry("anchor-$i", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Root certificates of the certificate authorities Cloudflare uses for workers.dev (resource tls/relay-roots.pem). */
    fun bundledRelayRoots(): List<X509Certificate> {
        val stream = Tls::class.java.getResourceAsStream("/tls/relay-roots.pem") ?: throw IllegalStateException("relay roots missing")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate } }
    }

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
        val clientContext = context().apply {
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
