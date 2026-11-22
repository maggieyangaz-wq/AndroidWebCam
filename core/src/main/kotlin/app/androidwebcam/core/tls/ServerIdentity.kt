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
import java.util.Base64
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

    /**
     * Public-key pin ("sha256/" + base64 of SHA-256 over the SubjectPublicKeyInfo exactly as encoded
     * in the certificate). Unlike the certificate fingerprint it survives certificate re-issues
     * (new LAN address, renewal), which keep the same key; used by the AI bridge on a computer.
     */
    val publicKeyPin: String = "sha256/" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo(certificate.encoded)),
    )

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

        /** The SubjectPublicKeyInfo element of a DER X.509 certificate, byte for byte (not re-encoded). */
        fun subjectPublicKeyInfo(der: ByteArray): ByteArray {
            val cert = readTlv(der, 0, der.size)
            require(cert.tag == 0x30) { "not a certificate" }
            val tbs = readTlv(der, cert.contentStart, cert.end)
            require(tbs.tag == 0x30) { "not a certificate" }
            var element = readTlv(der, tbs.contentStart, tbs.end)
            if (element.tag == 0xA0) element = readTlv(der, element.end, tbs.end) // [0] version
            // serialNumber, then signature, issuer, validity and subject precede the key.
            var pos = element.end
            repeat(4) { pos = readTlv(der, pos, tbs.end).end }
            val spki = readTlv(der, pos, tbs.end)
            require(spki.tag == 0x30) { "malformed certificate" }
            return der.copyOfRange(spki.start, spki.end)
        }

        private class Tlv(val tag: Int, val start: Int, val contentStart: Int, val end: Int)

        private fun readTlv(der: ByteArray, pos: Int, limit: Int): Tlv {
            require(pos + 2 <= limit) { "truncated DER" }
            val tag = der[pos].toInt() and 0xFF
            require(tag and 0x1F != 0x1F) { "unsupported DER tag" }
            var p = pos + 1
            val first = der[p++].toInt() and 0xFF
            val length = if (first < 0x80) {
                first
            } else {
                val n = first and 0x7F
                require(n in 1..3 && p + n <= limit) { "bad DER length" }
                var value = 0
                repeat(n) { value = (value shl 8) or (der[p++].toInt() and 0xFF) }
                value
            }
            require(length <= limit - p) { "truncated DER" }
            return Tlv(tag, pos, p, p + length)
        }

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
