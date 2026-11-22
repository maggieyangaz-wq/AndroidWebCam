package app.androidwebcam

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.androidwebcam.core.cloud.Ecdsa
import app.androidwebcam.core.cloud.RelayClient
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.security.CloudAgentStore
import app.androidwebcam.security.PhoneRelayIdentity
import app.androidwebcam.security.TlsIdentityStore
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamingService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Cloud AI access on a real Android system (the relay protocol itself: tools/run-relay-tests.sh). */
@RunWith(AndroidJUnit4::class)
class CloudDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.CAMERA)
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scale = InstrumentationRegistry.getArguments().getString("timeoutScale")?.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0

    private fun t(ms: Long): Long = (ms * scale).toLong()

    @After
    fun tearDown() {
        StreamingService.stop(context)
        waitUntil(t(20_000)) { StreamController.state.value.phase == Phase.IDLE }
        SettingsStore(context).save(StreamSettings()) // cloud access off again
    }

    /** What the relay checks: the pin of the presented key, and a raw P-256 signature over its challenge. */
    @Test
    fun keystoreKeySignsTheRelayChallenge() {
        val identity = TlsIdentityStore(context, AndroidLogger).obtain(listOf(java.net.InetAddress.getByName("127.0.0.1")))
        assertTrue("the phone's key lives in the Android Keystore", identity.hardwareBacked)
        val phone = PhoneRelayIdentity { identity }
        val message = "${RelayClient.PROTOCOL}\nrelay.example.workers.dev\nnonce-1234".toByteArray()
        val raw = phone.sign(message)
        assertEquals(64, raw.size)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(phone.spki))
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(message) }.verify(Ecdsa.rawToDer(raw)))
        val pin = "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(phone.spki))
        assertEquals("the relay's PHONE_PIN is the pin shown in the app", identity.publicKeyPin, pin)
        assertEquals(pin, TlsIdentityStore(context, AndroidLogger).savedPublicKeyPin())
        assertEquals(pin, ServerIdentity.pinOf(identity.certificate))
    }

    @Test
    fun cloudAgentKeysAreProtectedAndPicturesLimited() {
        val store = CloudAgentStore.get(context)
        store.revokeAll()
        val credential = store.create("Test agent")
        val listed = store.directory.list().single()
        assertEquals(credential.agentId, listed.id)
        assertEquals(credential.authHash, listed.authHash)
        assertArrayEquals(credential.e2eKey, store.directory.key(credential.agentId))
        assertNull(store.directory.admit(credential.agentId))
        assertEquals("one picture every 5 s per agent", 429, store.directory.admit(credential.agentId))
        val stored = context.getSharedPreferences("cloud_agents", Context.MODE_PRIVATE).getString("records", "")!!
        assertFalse("the key is stored encrypted", stored.contains(credential.e2eKeyText) || stored.contains(Base64.getEncoder().encodeToString(credential.e2eKey)))
        assertFalse("the relay token secret is not stored", stored.contains(credential.secret))
        store.revoke(credential.agentId)
        assertEquals(401, store.directory.admit(credential.agentId))
        assertNull(store.directory.key(credential.agentId))
        assertTrue(store.agents.value.isEmpty())
    }

    /** Cloud access on, but the relay cannot be reached: streaming works as usual and stops cleanly. */
    @Test
    fun unreachableRelayDoesNotDisturbStreaming() {
        SettingsStore(context).save(
            StreamSettings(fps = 15, orientation = 0, port = 8443, cloudEnabled = true, cloudRelay = "https://127.0.0.1", cloudWifiOnly = false),
        )
        val main = launchMainActivity(t(60_000))
        try {
            instrumentation.runOnMainSync { StreamingService.start(main) }
            waitUntil(t(90_000)) { StreamController.state.value.phase == Phase.RUNNING }
            waitUntil(t(60_000)) { StreamController.state.value.cloudStatus == RelayClient.Status.RETRYING }
            assertEquals(Phase.RUNNING, StreamController.state.value.phase)
            assertTrue(StreamController.state.value.phonePin!!.startsWith("sha256/"))
            instrumentation.runOnMainSync { StreamingService.stop(main) }
            waitUntil(t(30_000)) { StreamController.state.value.phase == Phase.IDLE }
            Thread.sleep(t(2_000))
            var alive = false
            instrumentation.runOnMainSync { alive = !main.isDestroyed && !main.isFinishing }
            assertTrue(alive)
            assertNull(StreamController.state.value.cloudStatus)
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = StreamController.state.value
            if (s.phase == Phase.ERROR) fail("streaming failed: ${s.error} ${s.errorDetail}")
            if (condition()) return
            Thread.sleep(100)
        }
        fail("timeout, last state: ${StreamController.state.value}")
    }
}
