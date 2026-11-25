package app.androidwebcam.core

import app.androidwebcam.core.cloud.RelayClient
import app.androidwebcam.core.tls.IdentityKeyManager
import app.androidwebcam.core.tls.SelfSignedCertificate
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.core.tls.Tls
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** The pieces used on Android 6-9: bundled relay root certificates, own trust anchors, TLS provider. */
class LegacyTlsTest {
    @After
    fun resetProvider() {
        Tls.provider = null
    }

    @Test
    fun bundledRelayRootsAreTheExpectedCertificateAuthorities() {
        val roots = Tls.bundledRelayRoots()
        val fingerprints = roots.map { ServerIdentity.fingerprint(it.encoded) }.toSet()
        // Let's Encrypt, Google Trust Services, SSL.com and Sectigo (USERTrust): the CAs Cloudflare uses.
        assertEquals(EXPECTED_ROOTS, fingerprints)
        val inFiveYears = Instant.now().plus(Duration.ofDays(5 * 365))
        for (root in roots) {
            root.checkValidity()
            root.verify(root.publicKey) // self-signed
            assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            assertTrue("not a CA: ${root.subjectX500Principal}", root.basicConstraints >= 0)
            assertTrue("expires soon: ${root.subjectX500Principal}", root.notAfter.toInstant().isAfter(inFiveYears))
        }
    }

    @Test
    fun trustManagerTrustsExactlyItsAnchors() {
        val trusted = relayCertificate().certificate
        val other = relayCertificate().certificate
        val trust = Tls.trustManager(Tls.bundledRelayRoots() + trusted)
        trust.checkServerTrusted(arrayOf(trusted), "ECDHE_ECDSA")
        try {
            trust.checkServerTrusted(arrayOf(other), "ECDHE_ECDSA")
            fail("a certificate from an unknown issuer must be refused")
        } catch (_: CertificateException) {
        }
        assertEquals(EXPECTED_ROOTS.size + 1, trust.acceptedIssuers.size)
    }

    @Test
    fun relayConnectionUsesTheGivenTrustAnchors() {
        val relay = relayCertificate()
        val trusted = { Tls.trustManager(listOf(relay.certificate)) }
        val untrusted = { Tls.trustManager(listOf(relayCertificate().certificate)) }
        assertEquals("TLSv1.3", connectToRelay(relay, trusted))
        assertRefused(relay, untrusted)

        // The same with an explicitly chosen TLS implementation, as on Android 6-9 (there: Conscrypt).
        Tls.provider = SSLContext.getDefault().provider
        assertEquals("TLSv1.3", connectToRelay(relay, trusted))
        assertRefused(relay, untrusted)
    }

    private fun assertRefused(relay: ServerIdentity, trust: () -> X509TrustManager) {
        try {
            connectToRelay(relay, trust)
            fail("the relay's certificate must not be trusted")
        } catch (_: SSLHandshakeException) {
        }
    }

    /** A relay certificate for relay.test (an Android 6-9 phone: no system roots involved). */
    private fun relayCertificate(): ServerIdentity {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val cert = SelfSignedCertificate.create(kp.public, SelfSignedCertificate.softwareSigner(kp.private), emptyList(), dnsNames = listOf("relay.test"))
        return ServerIdentity(kp.private, cert, hardwareBacked = false)
    }

    /** Connects like the phone to a TLS server on this machine named relay.test; returns the protocol. */
    private fun connectToRelay(relay: ServerIdentity, trust: () -> X509TrustManager): String {
        val server = Tls.context("TLS").apply { init(arrayOf(IdentityKeyManager(relay)), null, null) }
            .serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.use {
            val accepted = thread(isDaemon = true) {
                try {
                    (server.accept() as SSLSocket).use { s ->
                        s.soTimeout = 5_000
                        s.startHandshake()
                        s.outputStream.write(1)
                        s.outputStream.flush()
                        s.inputStream.read()
                    }
                } catch (_: Exception) {
                }
            }
            val connector = RelayClient.Connector.tls(
                connectRaw = { _, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs) } },
                trust = trust,
            )
            val protocol = (connector.open("relay.test", server.localPort, 5_000) as SSLSocket).use {
                assertEquals(1, it.inputStream.read())
                assertEquals(relay.certificate, it.session.peerCertificates[0] as X509Certificate)
                it.outputStream.write(2)
                it.session.protocol
            }
            accepted.join(5_000)
            return protocol
        }
    }

    private companion object {
        val EXPECTED_ROOTS = setOf(
            // ISRG Root X1, X2 (Let's Encrypt)
            "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6",
            "69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70",
            // GTS Root R1, R3, R4 (Google Trust Services)
            "D9:47:43:2A:BD:E7:B7:FA:90:FC:2E:6B:59:10:1B:12:80:E0:E1:C7:E4:E4:0F:A3:C6:88:7F:FF:57:A7:F4:CF",
            "34:D8:A7:3E:E2:08:D9:BC:DB:0D:95:65:20:93:4B:4E:40:E6:94:82:59:6E:8B:6F:73:C8:42:6B:01:0A:6F:48",
            "34:9D:FA:40:58:C5:E2:63:12:3B:39:8A:E7:95:57:3C:4E:13:13:C8:3F:E6:8F:93:55:6C:D5:E8:03:1B:3C:7D",
            // SSL.com TLS RSA / ECC Root CA 2022, SSL.com Root Certification Authority RSA / ECC
            "8F:AF:7D:2E:2C:B4:70:9B:B8:E0:B3:36:66:BF:75:A5:DD:45:B5:DE:48:0F:8E:A8:D4:BF:E6:BE:BC:17:F2:ED",
            "C3:2F:FD:9F:46:F9:36:D1:6C:36:73:99:09:59:43:4B:9A:D6:0A:AF:BB:9E:7C:F3:36:54:F1:44:CC:1B:A1:43",
            "85:66:6A:56:2E:E0:BE:5C:E9:25:C1:D8:89:0A:6F:76:A8:7E:C1:6D:4D:7D:5F:29:EA:74:19:CF:20:12:3B:69",
            "34:17:BB:06:CC:60:07:DA:1B:96:1C:92:0B:8A:B4:CE:3F:AD:82:0E:4A:A3:0B:9A:CB:C4:A7:4E:BD:CE:BC:65",
            // USERTrust RSA / ECC Certification Authority (Sectigo)
            "E7:93:C9:B0:2F:D8:AA:13:E2:1C:31:22:8A:CC:B0:81:19:64:3B:74:9C:89:89:64:B1:74:6D:46:C3:D4:CB:D2",
            "4F:F4:60:D5:4B:9C:86:DA:BF:BC:FC:57:12:E0:40:0D:2B:ED:3F:BC:4D:4F:BD:AA:86:E0:6A:DC:D2:A9:AD:7A",
        )
    }
}
