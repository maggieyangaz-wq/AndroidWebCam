package app.androidwebcam.core.stream

import java.io.ByteArrayOutputStream

/** H.264 bitstream helpers: Annex B parsing, AVCC conversion and avcC construction. */
object Avc {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Splits an Annex B buffer into NAL units (start codes removed). */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val result = ArrayList<ByteArray>()
        var i = offset
        var nalStart = -1
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (nalStart >= 0) addNal(data, nalStart, i, result)
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart >= 0) {
            if (nalStart < end) result.add(data.copyOfRange(nalStart, end))
        } else if (length > 0) {
            result.add(data.copyOfRange(offset, end)) // no start code: treat as one NAL unit
        }
        return result
    }

    private fun addNal(data: ByteArray, start: Int, startCodePos: Int, out: MutableList<ByteArray>) {
        var end = startCodePos
        while (end > start && data[end - 1].toInt() == 0) end-- // zero byte of a 4-byte start code
        if (end > start) out.add(data.copyOfRange(start, end))
    }

    /** Joins NAL units with 4-byte big-endian length prefixes (AVCC / "avc" format). */
    fun toAvcc(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        for (nal in nals) {
            val n = nal.size
            out.write(n ushr 24); out.write((n ushr 16) and 0xFF); out.write((n ushr 8) and 0xFF); out.write(n and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** RFC 6381 codec string, e.g. "avc1.64001f". */
    fun codecString(sps: ByteArray): String {
        require(sps.size >= 4 && nalType(sps) == NAL_SPS) { "not an SPS" }
        return String.format("avc1.%02x%02x%02x", sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
    }

    /** AVCDecoderConfigurationRecord (ISO/IEC 14496-15) for WebCodecs' `description`. */
    fun decoderConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4 && nalType(sps) == NAL_SPS) { "not an SPS" }
        require(pps.isNotEmpty() && nalType(pps) == NAL_PPS) { "not a PPS" }
        val profile = sps[1].toInt() and 0xFF
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(profile)
        out.write(sps[2].toInt() and 0xFF)
        out.write(sps[3].toInt() and 0xFF)
        out.write(0xFF) // reserved(6) + lengthSizeMinusOne = 3
        out.write(0xE1) // reserved(3) + numOfSequenceParameterSets = 1
        out.write(sps.size ushr 8); out.write(sps.size and 0xFF); out.write(sps)
        out.write(1) // numOfPictureParameterSets
        out.write(pps.size ushr 8); out.write(pps.size and 0xFF); out.write(pps)
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            // High profiles: 4:2:0, 8-bit (what Android hardware encoders produce), no SPS ext.
            out.write(0xFC or 1)
            out.write(0xF8)
            out.write(0xF8)
            out.write(0)
        }
        return out.toByteArray()
    }

    /** Result of converting one encoder output buffer. */
    class AccessUnit(val avcc: ByteArray, val isKey: Boolean, val sps: ByteArray?, val pps: ByteArray?)

    /**
     * Converts an encoder access unit from Annex B to AVCC, removing parameter sets and
     * access unit delimiters (parameter sets travel separately in the decoder config).
     */
    fun convertAccessUnit(annexB: ByteArray, offset: Int = 0, length: Int = annexB.size - offset): AccessUnit {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var key = false
        val keep = ArrayList<ByteArray>()
        for (nal in splitAnnexB(annexB, offset, length)) {
            when (nalType(nal)) {
                NAL_SPS -> sps = nal
                NAL_PPS -> pps = nal
                NAL_AUD -> Unit
                NAL_IDR -> { key = true; keep.add(nal) }
                else -> keep.add(nal)
            }
        }
        return AccessUnit(toAvcc(keep), key, sps, pps)
    }
}
