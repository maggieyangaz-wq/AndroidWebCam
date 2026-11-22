package app.androidwebcam.core

import app.androidwebcam.core.tls.IdentityKeyManager
import app.androidwebcam.core.tls.SelfSignedCertificate
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.core.tls.Tls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

class CertificateAndTlsTest {
    private val lan = InetAddress.getByName("192.168.1.23")

    @Test
    fun certificateMeetsBrowserPolicies() {
        val identity = TestSupport.softwareIdentity(listOf(lan, TestSupport.loopback))
        val cert = identity.certificate
        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertEquals(3, cert.version)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals("CN=Android WebCam", cert.subjectX500Principal.name)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage)
        assertTrue(cert.keyUsage[0]) // digitalSignature
        assertFalse(cert.keyUsage.drop(1).any { it })
        assertEquals(-1, cert.basicConstraints) // not a CA
        val days = Duration.between(cert.notBefore.toInstant(), cert.notAfter.toInstant()).toDays()
        assertTrue("validity must stay below 398 days, was $days", days < 398)
        assertEquals(setOf("192.168.1.23", "127.0.0.1"), ServerIdentity.subjectAltIps(cert))
        assertTrue(identity.coversAll(listOf(lan)))
        assertFalse(identity.coversAll(listOf(InetAddress.getByName("10.0.0.2"))))
        assertTrue(identity.fingerprintSha256.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertFalse(identity.expiresWithin(Duration.ofDays(30)))
        assertTrue(identity.expiresWithin(Duration.ofDays(400)))
    }

    @Test
    fun certificatesAfter2049UseGeneralizedTime() {
        val base = TestSupport.softwareIdentity()
        val cert = SelfSignedCertificate.create(
            base.certificate.publicKey,
            SelfSignedCertificate.softwareSigner(base.privateKey),
            listOf(lan),
            now = Instant.parse("2049-12-01T00:00:00Z"),
        )
        assertEquals(Instant.parse("2049-11-30T23:00:00Z"), cert.notBefore.toInstant())
        assertTrue(cert.notAfter.toInstant().isAfter(Instant.parse("2050-12-01T00:00:00Z")))
    }

    @Test
    fun selfTestHandshakeSucceedsWithMatchingIdentity() {
        val identity = TestSupport.softwareIdentity()
        Tls.selfTest(Tls.serverContext(IdentityKeyManager(identity)), identity.certificate)
    }

    @Test
    fun selfTestFailsWhenKeyDoesNotMatchCertificate() {
        val a = TestSupport.softwareIdentity()
        val b = TestSupport.softwareIdentity()
        val broken = ServerIdentity(b.privateKey, a.certificate, hardwareBacked = false)
        try {
            Tls.selfTest(Tls.serverContext(IdentityKeyManager(broken)), broken.certificate)
            fail("handshake with mismatched key must fail")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun publicKeyPinCoversTheKeyNotTheCertificate() {
        val first = TestSupport.softwareIdentity()
        val spki = ServerIdentity.subjectPublicKeyInfo(first.certificate.encoded)
        assertTrue("SPKI exactly as in the certificate", spki.contentEquals(first.certificate.publicKey.encoded))
        assertTrue(first.publicKeyPin.matches(Regex("sha256/[A-Za-z0-9+/]{43}=")))
        // A certificate re-issued for another LAN address with the same key keeps the pin.
        val reissued = ServerIdentity(
            first.privateKey,
            SelfSignedCertificate.create(
                first.certificate.publicKey,
                SelfSignedCertificate.softwareSigner(first.privateKey),
                listOf(InetAddress.getByName("192.168.1.23")),
            ),
            hardwareBacked = false,
        )
        assertEquals(first.publicKeyPin, reissued.publicKeyPin)
        assertFalse(first.fingerprintSha256 == reissued.fingerprintSha256)
        assertFalse(first.publicKeyPin == TestSupport.softwareIdentity().publicKeyPin)
    }

    @Test
    fun aiToolIsDescribedAsSuch() {
        assertEquals("AI tool · Windows", app.androidwebcam.core.server.UserAgents.describe("AndroidWebCam-AI/1.0.0 (Windows)"))
        assertEquals("AI tool · macOS", app.androidwebcam.core.server.UserAgents.describe("AndroidWebCam-AI/1.0.0 (Macintosh)"))
    }
}
