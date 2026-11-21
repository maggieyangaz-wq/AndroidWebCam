package app.androidwebcam.core.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Minimal DER encoder, just enough to build an X.509 v3 certificate. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        when {
            length < 0x80 -> out.write(length)
            length <= 0xFF -> {
                out.write(0x81); out.write(length)
            }
            length <= 0xFFFF -> {
                out.write(0x82); out.write(length ushr 8); out.write(length and 0xFF)
            }
            else -> {
                out.write(0x83); out.write(length ushr 16); out.write((length ushr 8) and 0xFF); out.write(length and 0xFF)
            }
        }
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))
    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())
    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))
    fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)
    fun utf8String(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    /** BIT STRING with [unusedBits] padding bits in the last octet. */
    fun bitString(content: ByteArray, unusedBits: Int = 0): ByteArray =
        tlv(0x03, byteArrayOf(unusedBits.toByte()) + content)

    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xA0 or tagNumber, content)
    fun implicitPrimitive(tagNumber: Int, content: ByteArray): ByteArray = tlv(0x80 or tagNumber, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "bad OID" }
        val out = ByteArrayOutputStream()
        writeBase128(out, parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) writeBase128(out, parts[i])
        return tlv(0x06, out.toByteArray())
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
        var v = value
        val stack = ArrayList<Int>()
        stack.add((v and 0x7F).toInt())
        v = v ushr 7
        while (v > 0) {
            stack.add(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        for (i in stack.indices.reversed()) out.write(stack[i])
    }

    private val utcFormat = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
    private val generalizedFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    /** RFC 5280: UTCTime through 2049, GeneralizedTime afterwards. */
    fun time(instant: Instant): ByteArray {
        val year = instant.atZone(ZoneOffset.UTC).year
        return if (year in 1950..2049) {
            tlv(0x17, utcFormat.format(instant).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, generalizedFormat.format(instant).toByteArray(Charsets.US_ASCII))
        }
    }
}
