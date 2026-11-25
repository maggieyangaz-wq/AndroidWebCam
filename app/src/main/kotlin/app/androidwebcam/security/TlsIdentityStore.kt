package app.androidwebcam.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.androidwebcam.core.Logger
import app.androidwebcam.core.tls.IdentityKeyManager
import app.androidwebcam.core.tls.SelfSignedCertificate
import app.androidwebcam.core.tls.ServerIdentity
import app.androidwebcam.core.tls.Tls
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import javax.security.auth.x500.X500Principal

/**
 * TLS identity of the phone. The ECDSA P-256 private key is generated inside the
 * Android Keystore (TEE on this class of device) and is not exportable. The public
 * certificate is re-issued when it is about to expire or when the phone gets a LAN
 * address that it does not cover yet. A complete TLS 1.3 handshake is performed
 * in memory before the identity is used; if the hardware key cannot be used by the TLS
 * stack on a particular device, a temporary in-memory key is used instead.
 */
class TlsIdentityStore(context: Context, private val logger: Logger) {
    private val certFile = AtomicFile(File(context.noBackupFilesDir, "tls_server_cert.der"))

    init {
        TlsSupport.install(logger)
    }

    @Synchronized
    fun obtain(addresses: List<InetAddress>): ServerIdentity {
        try {
            return hardwareBacked(addresses)
        } catch (e: Exception) {
            logger.w("hardware-backed TLS key unusable, falling back to a temporary key", e)
        }
        return temporary(addresses)
    }

    /** Returns a re-issued identity if [current] does not cover [addresses], else null. */
    @Synchronized
    fun refreshFor(current: ServerIdentity, addresses: List<InetAddress>): ServerIdentity? {
        if (current.coversAll(addresses) && !current.expiresWithin(RENEW_BEFORE)) return null
        return if (current.hardwareBacked) {
            try { hardwareBacked(addresses) } catch (e: Exception) { logger.w("re-issue failed", e); null }
        } else {
            temporary(addresses)
        }
    }

    private fun hardwareBacked(addresses: List<InetAddress>): ServerIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generateKey()
        val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(ALIAS).publicKey
        val stored = loadCertificate()?.takeIf { it.publicKey.encoded.contentEquals(publicKey.encoded) }
        var identity = stored?.let { ServerIdentity(privateKey, it, hardwareBacked = true) }
        if (identity == null || identity.expiresWithin(RENEW_BEFORE) || !identity.coversAll(addresses)) {
            // Keep previously used LAN addresses so switching networks does not change the certificate each time.
            val previous = stored?.let { ServerIdentity.subjectAltIps(it) }.orEmpty().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val sans = (addresses + previous).distinct().take(MAX_SAN_ADDRESSES)
            val certificate = SelfSignedCertificate.create(publicKey, SelfSignedCertificate.softwareSigner(privateKey), sans)
            saveCertificate(certificate)
            identity = ServerIdentity(privateKey, certificate, hardwareBacked = true)
        }
        Tls.selfTest(Tls.serverContext(IdentityKeyManager(identity)), identity.certificate)
        return identity
    }

    private fun temporary(addresses: List<InetAddress>): ServerIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val certificate = SelfSignedCertificate.create(pair.public, SelfSignedCertificate.softwareSigner(pair.private), addresses)
        val identity = ServerIdentity(pair.private, certificate, hardwareBacked = false)
        Tls.selfTest(Tls.serverContext(IdentityKeyManager(identity)), identity.certificate)
        return identity
    }

    private fun generateKey() {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // NONE is needed by the TLS stack, which signs pre-computed handshake digests.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=" + SelfSignedCertificate.COMMON_NAME))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    /** Public-key pin of the stored certificate (the phone's key), e.g. to configure a cloud relay. */
    fun savedPublicKeyPin(): String? = loadCertificate()?.let { ServerIdentity.pinOf(it) }

    private fun loadCertificate(): X509Certificate? = try {
        val bytes = certFile.readFully()
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    } catch (e: Exception) {
        null
    }

    private fun saveCertificate(certificate: X509Certificate) {
        val out = certFile.startWrite()
        try {
            out.write(certificate.encoded)
            certFile.finishWrite(out)
        } catch (e: Exception) {
            certFile.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "awc_tls_server_v1"
        const val MAX_SAN_ADDRESSES = 8
        val RENEW_BEFORE: Duration = Duration.ofDays(30)
    }
}
