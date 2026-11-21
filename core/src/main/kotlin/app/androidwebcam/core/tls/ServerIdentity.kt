package app.androidwebcam.core.tls

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** TLS server identity: an EC private key (ideally hardware-backed) plus its certificate. */
class ServerIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    /** True when the private key lives in the Android Keystore (TEE) and cannot be exported. */
    val hardwareBacked: Boolean,
) {
    /** SHA-256 over the DER certificate, as shown by browsers' certificate viewers. */
    val fingerprintSha256: String = fingerprint(certificate.encoded)

    fun coversAll(addresses: Collection<InetAddress>): Boolean {
        val sans = subjectAltIps(certificate)
        return addresses.filterIsInstance<Inet4Address>().all { it.hostAddress in sans }
    }

    fun expiresWithin(duration: Duration, now: Instant = Instant.now()): Boolean =
        certificate.notAfter.toInstant().isBefore(now.plus(duration)) ||
            certificate.notBefore.toInstant().isAfter(now.plus(Duration.ofMinutes(5)))

    companion object {
        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { String.format("%02X", it) }

        fun subjectAltIps(certificate: X509Certificate): Set<String> =
            certificate.subjectAlternativeNames
                ?.filter { it.size >= 2 && it[0] == 7 }
                ?.mapNotNull { it[1] as? String }
                ?.toSet()
                ?: emptySet()
    }
}

/**
 * Key manager that always presents the current [ServerIdentity]. The identity can be
 * swapped at runtime (e.g. when the certificate is re-issued for a new LAN address);
 * new handshakes pick it up immediately.
 */
class IdentityKeyManager(initial: ServerIdentity) : X509ExtendedKeyManager() {
    @Volatile
    var identity: ServerIdentity = initial

    /**
     * Always offers the single identity. TLS stacks verify the key algorithm themselves
     * (SunJSSE skips non-matching types; Conscrypt asks for "RSA" and "EC" and uses the EC key).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun aliasFor(keyType: String?): String = ALIAS

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = aliasFor(keyType)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = aliasFor(keyType)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = arrayOf(aliasFor(keyType))
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(identity.certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null

    // This is a server-only key manager: never offer client certificates.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

    companion object {
        const val ALIAS = "awc"
    }
}
