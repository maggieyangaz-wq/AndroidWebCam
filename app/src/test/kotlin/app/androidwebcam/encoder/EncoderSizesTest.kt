package app.androidwebcam.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EncoderSizesTest {
    @Test
    fun startsWithRequestedSizeAndKeepsOrientationAndAspect() {
        for ((w, h) in listOf(720 to 1280, 1280 to 720, 1080 to 1920, 540 to 960)) {
            val c = EncoderSizes.candidates(w, h)
            assertEquals(w to h, c.first())
            val aspect = w.toDouble() / h
            for ((cw, ch) in c) {
                assertTrue("orientation kept for $cw x $ch", (cw > ch) == (w > h))
                assertTrue("even size $cw x $ch", cw % 2 == 0 && ch % 2 == 0)
                assertTrue("aspect kept for $cw x $ch", abs(cw.toDouble() / ch - aspect) / aspect < 0.02)
                assertTrue(minOf(cw, ch) >= 144)
            }
            val areas = c.map { it.first * it.second }
            assertTrue("largest first", areas.first() == areas.maxOrNull())
        }
    }

    @Test
    fun offersSizesThatFitSmallEncoderLimits() {
        // e.g. an encoder limited to 896x896 (the emulator's software encoder class of limits)
        val fit = EncoderSizes.candidates(720, 1280).first { (w, h) -> w <= 896 && h <= 896 }
        assertEquals(896, fit.second)
        assertTrue(fit.first in 496..512)
    }
}
