package app.androidwebcam.core.cloud

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of pictures for cloud agents: the phone encrypts each picture with the
 * agent's own key, which only the phone and the agent know (never the relay).
 *
 * Format: "AWC1" | IV (12) | AES-256-GCM(time taken in ms (8, big endian) | JPEG) incl. 16-byte tag,
 * with "AWC1" + agent id as additional data. The time lets the agent reject old (replayed) pictures.
 */
object CloudCrypto {
    private val MAGIC = "AWC1".toByteArray(Charsets.US_ASCII)
    const val KEY_BYTES = 32

    fun seal(key: ByteArray, agentId: String, takenAtMs: Long, picture: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(key.size == KEY_BYTES) { "key size" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC + agentId.toByteArray(Charsets.US_ASCII))
        val plain = ByteBuffer.allocate(8 + picture.size).putLong(takenAtMs).put(picture).array()
        return MAGIC + iv + cipher.doFinal(plain)
    }

    class Opened(val takenAtMs: Long, val picture: ByteArray)

    /** The agent's side (used by tests); throws if the data was altered or is for another agent. */
    fun open(key: ByteArray, agentId: String, sealed: ByteArray): Opened {
        require(sealed.size >= 4 + 12 + 8 + 16 && sealed.copyOfRange(0, 4).contentEquals(MAGIC)) { "not an Android WebCam picture" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 4, 12))
        cipher.updateAAD(MAGIC + agentId.toByteArray(Charsets.US_ASCII))
        val plain = cipher.doFinal(sealed, 16, sealed.size - 16)
        return Opened(ByteBuffer.wrap(plain, 0, 8).long, plain.copyOfRange(8, plain.size))
    }
}

/**
 * A cloud agent's credential, created on the phone and shown once:
 *  - token "awcc_<id>_<secret>": presented to the relay (which only knows SHA-256(secret));
 *  - e2e key: decrypts the pictures; never sent to the relay.
 */
class CloudCredential(val agentId: String, val secret: String, val e2eKey: ByteArray) {
    val token: String get() = "awcc_${agentId}_$secret"
    val authHash: String get() = authHash(secret)
    val e2eKeyText: String get() = B64URL.encodeToString(e2eKey)

    companion object {
        private val B64URL = Base64.getUrlEncoder().withoutPadding()

        fun create(random: SecureRandom = SecureRandom()): CloudCredential = CloudCredential(
            agentId = B64URL.encodeToString(ByteArray(6).also(random::nextBytes)),
            secret = B64URL.encodeToString(ByteArray(32).also(random::nextBytes)),
            e2eKey = ByteArray(CloudCrypto.KEY_BYTES).also(random::nextBytes),
        )

        fun authHash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII)).joinToString("") { String.format("%02x", it) }
    }
}

/** ECDSA signature encoding: Java/Android produce DER, the relay (WebCrypto) expects raw r|s. */
object Ecdsa {
    fun derToRaw(der: ByteArray, size: Int = 32): ByteArray {
        var p = 0
        fun next(): Int = der[p++].toInt() and 0xFF
        fun length(): Int {
            val first = next()
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7F) { n = (n shl 8) or next() }
            return n
        }
        require(next() == 0x30) { "not a DER signature" }
        length()
        val out = ByteArray(size * 2)
        for (part in 0..1) {
            require(next() == 0x02) { "not a DER signature" }
            val len = length()
            var value = der.copyOfRange(p, p + len)
            p += len
            while (value.size > size && value[0] == 0.toByte()) value = value.copyOfRange(1, value.size)
            require(value.size <= size) { "integer too large" }
            System.arraycopy(value, 0, out, part * size + size - value.size, value.size)
        }
        return out
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        val half = raw.size / 2
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, half)) + integer(raw.copyOfRange(half, raw.size))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
