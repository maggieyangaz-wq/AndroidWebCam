package app.androidwebcam.core

import app.androidwebcam.core.cloud.CloudCredential
import app.androidwebcam.core.cloud.CloudCrypto
import app.androidwebcam.core.cloud.CloudInstructions
import app.androidwebcam.core.cloud.Ecdsa
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CloudTest {
    private val picture = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(5000) { it.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun picturesAreEncryptedForOneAgent() {
        val agent = CloudCredential.create()
        val sealed = CloudCrypto.seal(agent.e2eKey, agent.agentId, 1_700_000_000_000, picture)
        assertEquals("AWC1", String(sealed.copyOfRange(0, 4)))
        assertTrue("the picture must not be readable", sealed.toList().windowed(16).none { it == picture.copyOfRange(100, 116).toList() })
        val opened = CloudCrypto.open(agent.e2eKey, agent.agentId, sealed)
        assertEquals(1_700_000_000_000, opened.takenAtMs)
        assertArrayEquals(picture, opened.picture)

        val other = CloudCredential.create()
        expectFailure { CloudCrypto.open(other.e2eKey, agent.agentId, sealed) } // another agent's key
        expectFailure { CloudCrypto.open(agent.e2eKey, other.agentId, sealed) } // bound to the agent id
        val tampered = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        expectFailure { CloudCrypto.open(agent.e2eKey, agent.agentId, tampered) }
    }

    @Test
    fun credentialsKeepTheirParts() {
        val c = CloudCredential.create()
        assertTrue(c.token.matches(Regex("awcc_[A-Za-z0-9_-]{8}_[A-Za-z0-9_-]{43}")))
        assertTrue(c.authHash.matches(Regex("[0-9a-f]{64}")))
        assertFalse("the relay token never contains the decryption key", c.token.contains(c.e2eKeyText))
        val command = CloudInstructions.command("https://relay.example.workers.dev/", c)
        assertTrue(command.contains("Bearer ${c.token}") && command.contains("https://relay.example.workers.dev/v1/snapshot"))
        assertTrue(command.contains(c.e2eKeyText) && command.contains(" ${c.agentId} "))
        assertTrue(CloudInstructions.text("https://relay.example.workers.dev", c, chinese = true).contains(command))
    }

    @Test
    fun derAndRawSignaturesConvert() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(20) {
            val kp = kpg.generateKeyPair()
            val message = "AWC-RELAY-1\nrelay.example\nnonce$it".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(message) }.sign()
            val raw = Ecdsa.derToRaw(der)
            assertEquals(64, raw.size)
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(message) }
            assertTrue(verifier.verify(Ecdsa.rawToDer(raw)))
        }
    }

    @Test
    fun relayCertificateHostCheck() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        // A certificate with DNS names, built like the phone's own (IP) certificates in tests.
        val cert = app.androidwebcam.core.tls.SelfSignedCertificate.create(
            kp.public, app.androidwebcam.core.tls.SelfSignedCertificate.softwareSigner(kp.private), emptyList(),
            dnsNames = listOf("*.sp-example.workers.dev", "relay.example.org"),
        )
        val check = { host: String -> app.androidwebcam.core.cloud.RelayClient.Connector.certificateMatches(host, cert) }
        assertTrue(check("awc-relay-1.sp-example.workers.dev"))
        assertTrue(check("RELAY.example.org"))
        assertFalse(check("a.b.sp-example.workers.dev")) // a wildcard covers one label only
        assertFalse(check("sp-example.workers.dev"))
        assertFalse(check("evil-relay.example.org"))
        assertFalse(check("relay.example.org.evil.com"))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("must fail")
        } catch (expected: Exception) {
        }
    }
}
