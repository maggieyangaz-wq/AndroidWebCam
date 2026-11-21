package app.androidwebcam.core.tls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

/**
 * Builds a self-signed ECDSA P-256 server certificate that satisfies the stricter
 * platform policies (Apple/Chrome): SAN present, EKU serverAuth, SHA-256 signature
 * and a validity period below 398 days. Browsers will still warn because the issuer
 * is not a public CA; users verify the fingerprint shown on the phone instead.
 */
object SelfSignedCertificate {
    const val COMMON_NAME = "Android WebCam"
    val VALIDITY: Duration = Duration.ofDays(397)

    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

    /** Signs data with SHA256withECDSA and returns the DER signature. */
    fun interface Signer {
        fun sign(data: ByteArray): ByteArray
    }

    fun softwareSigner(privateKey: PrivateKey): Signer = Signer { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    fun create(
        publicKey: PublicKey,
        signer: Signer,
        ipAddresses: List<InetAddress>,
        dnsNames: List<String> = emptyList(),
        now: Instant = Instant.now(),
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        require(publicKey.algorithm == "EC") { "Only EC keys are supported" }
        val serialBytes = ByteArray(16).also(random::nextBytes)
        val serial = BigInteger(1, serialBytes).setBit(120) // positive, non-zero, fixed size
        val algorithm = Der.sequence(Der.oid(OID_ECDSA_SHA256))
        val name = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(COMMON_NAME))))
        val notBefore = now.minus(Duration.ofHours(1))
        val notAfter = notBefore.plus(VALIDITY)
        val validity = Der.sequence(Der.time(notBefore), Der.time(notAfter))

        val altNames = ArrayList<ByteArray>()
        for (dns in dnsNames.distinct()) altNames.add(Der.implicitPrimitive(2, dns.toByteArray(Charsets.US_ASCII)))
        for (ip in ipAddresses.distinct()) {
            if (ip is Inet4Address) altNames.add(Der.implicitPrimitive(7, ip.address))
        }
        if (altNames.isEmpty()) altNames.add(Der.implicitPrimitive(2, "localhost".toByteArray(Charsets.US_ASCII)))

        val extensions = Der.explicit(
            3,
            Der.sequence(
                extension(OID_BASIC_CONSTRAINTS, critical = true, value = Der.sequence()),
                // digitalSignature only: bit 0 set, 7 unused bits.
                extension(OID_KEY_USAGE, critical = true, value = Der.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7)),
                extension(OID_EXT_KEY_USAGE, critical = false, value = Der.sequence(Der.oid(OID_SERVER_AUTH))),
                extension(OID_SUBJECT_ALT_NAME, critical = false, value = Der.sequence(*altNames.toTypedArray())),
            ),
        )
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(2)), // v3
            Der.integer(serial),
            algorithm,
            name,
            validity,
            name,
            publicKey.encoded, // SubjectPublicKeyInfo
            extensions,
        )
        val signature = signer.sign(tbs)
        val certificate = Der.sequence(tbs, algorithm, Der.bitString(signature))
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        parsed.verify(publicKey) // never hand out a certificate that does not verify
        return parsed
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        } else {
            Der.sequence(Der.oid(oid), Der.octetString(value))
        }
}
