package app.androidwebcam.security

import android.os.Build
import app.androidwebcam.core.Logger
import app.androidwebcam.core.tls.Tls
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * TLS on Android 6-9 (API 23-28). Their built-in TLS has no TLS 1.3, and their root certificates
 * may be years old, so there the app uses:
 *  - its bundled copy of Conscrypt (Google's TLS library, built into Android 10+) for all TLS,
 *    i.e. the same TLS 1.3 as on newer phones, independent of the phone's outdated system libraries;
 *  - for the relay: the system's root certificates plus the bundled ones of the certificate
 *    authorities Cloudflare uses ([Tls.bundledRelayRoots]).
 * Android 10+ keeps using the system's TLS and root certificates.
 */
object TlsSupport {
    private val legacy = Build.VERSION.SDK_INT < 29

    /** Must run before the first TLS connection; does nothing on Android 10+. */
    @Synchronized
    fun install(logger: Logger) {
        if (!legacy || Tls.provider != null) return
        try {
            Tls.provider = Conscrypt.newProvider()
        } catch (e: Throwable) { // e.g. UnsatisfiedLinkError: no native library for this CPU
            logger.e("bundled TLS 1.3 unavailable", e)
        }
    }

    /** Certificate validation for the relay; null = the system's. */
    val relayTrust: (() -> X509TrustManager)? = if (legacy) ({ legacyRelayTrust }) else null

    private val legacyRelayTrust: X509TrustManager by lazy {
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        // Only the system's own certificate authorities, not user-installed ones (like Android 7+).
        val anchors = system.aliases().toList().filter { it.startsWith("system:") }.mapNotNull { system.getCertificate(it) as? X509Certificate }
        Tls.trustManager(Tls.bundledRelayRoots() + anchors)
    }
}
