package app.androidwebcam

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamState
import app.androidwebcam.service.StreamingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Upgrade-install test, driven by tools/run-device-tests.sh:
 *  1. install the old APK, run [prepareBeforeUpgrade] (settings + TLS identity are created; the
 *     certificate fingerprint is logged and read back by the script);
 *  2. `adb install -r` the new APK over it (must succeed: same package and key, higher versionCode);
 *  3. run [verifyAfterUpgrade] with that fingerprint: in-place update, settings kept, same TLS
 *     certificate (viewers see no new certificate warning after updates), streaming still works.
 * Both tests are skipped in a normal test run.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.CAMERA)
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phase = args.getString("upgradePhase")
    private val scale = args.getString("timeoutScale")?.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0


    private fun t(ms: Long): Long = (ms * scale).toLong()

    @Test
    fun prepareBeforeUpgrade() {
        assumeTrue("run by tools/run-device-tests.sh only", phase == "prepare")
        SettingsStore(context).save(EXPECTED_SETTINGS)
        val fingerprint = streamOnce()
        println("AWC-TEST upgrade-fingerprint=$fingerprint") // read back by tools/run-device-tests.sh
    }

    @Test
    fun verifyAfterUpgrade() {
        assumeTrue("run by tools/run-device-tests.sh only", phase == "verify")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("new version installed", args.getString("expectedVersionCode")!!.toLong(), info.longVersionCode)
        assertTrue("in-place update, not a fresh install", info.lastUpdateTime > info.firstInstallTime)
        assertEquals("settings survive the update", EXPECTED_SETTINGS, SettingsStore(context).load())
        val fingerprint = streamOnce()
        assertEquals("TLS certificate survives the update", args.getString("expectedFingerprint"), fingerprint)
        println("AWC-TEST upgrade verify: versionCode ${info.longVersionCode}, settings and fingerprint kept")
    }

    /** Starts streaming from the foreground, checks that HTTPS answers, stops; returns the fingerprint. */
    private fun streamOnce(): String {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.runOnMainSync { StreamingService.start(activity) }
            val state = waitFor(t(90_000)) {
                it.phase == Phase.RUNNING && it.endpoints.isNotEmpty() && it.fingerprint != null
            }
            assertTrue("TLS key must live in the Android Keystore", state.hardwareKey)
            val host = state.endpoints.first().address.hostAddress!!
            val socket = pinnedContext(state.fingerprint!!).socketFactory.createSocket() as SSLSocket
            socket.use { s ->
                s.connect(InetSocketAddress(host, state.port), t(10_000).toInt())
                s.soTimeout = t(30_000).toInt()
                s.startHandshake()
                s.outputStream.write("GET / HTTP/1.1\r\nHost: $host:${state.port}\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                val status = BufferedReader(InputStreamReader(s.inputStream)).readLine() ?: ""
                assertTrue("viewer page must load: $status", status.startsWith("HTTP/1.1 200"))
            }
            return state.fingerprint!!
        } finally {
            StreamingService.stop(context)
            waitFor(t(20_000)) { it.phase == Phase.IDLE }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun waitFor(timeoutMs: Long, done: (StreamState) -> Boolean): StreamState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = StreamController.state.value
            if (s.phase == Phase.ERROR) fail("streaming failed: ${s.error} ${s.errorDetail}")
            if (done(s)) return s
            Thread.sleep(100)
        }
        fail("timeout, last state: ${StreamController.state.value}")
        throw AssertionError()
    }

    private fun pinnedContext(fingerprint: String): SSLContext {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString(":") { String.format("%02X", it) }
                if (actual != fingerprint) throw CertificateException("fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), SecureRandom()) }
    }

    private companion object {
        val EXPECTED_SETTINGS = StreamSettings(cameraId = null, quality = Quality.P540, fps = 24, orientation = 270, port = 8443)
    }
}
