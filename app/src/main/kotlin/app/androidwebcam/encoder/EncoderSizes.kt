package app.androidwebcam.encoder

import kotlin.math.abs
import kotlin.math.roundToInt

/** Candidate encoder sizes with the same aspect ratio, largest first. */
object EncoderSizes {
    /**
     * Starts with the requested size and shrinks the long side in 16 px steps down to a
     * short side of 144 px. Each step yields an even-sized and a 16-aligned variant.
     */
    fun candidates(width: Int, height: Int): List<Pair<Int, Int>> {
        require(width > 0 && height > 0)
        val result = LinkedHashSet<Pair<Int, Int>>()
        result.add(even(width) to even(height))
        val portrait = height > width
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val aspect = short.toDouble() / long
        var l = (long / 16) * 16
        while (l >= 16) {
            val s = l * aspect
            if (s < 144) break
            val evenShort = even(s.roundToInt())
            result.add(if (portrait) evenShort to l else l to evenShort)
            // Some encoders need 16-pixel alignment; only offer it if the picture stays undistorted.
            val alignedShort = ((s / 16).roundToInt() * 16).coerceAtLeast(16)
            if (alignedShort <= short && abs(alignedShort / l.toDouble() - aspect) / aspect < 0.02) {
                result.add(if (portrait) alignedShort to l else l to alignedShort)
            }
            l -= 16
        }
        return result.toList()
    }

    private fun even(v: Int) = (v / 2) * 2
}
