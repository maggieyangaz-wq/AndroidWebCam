package app.androidwebcam.core

import app.androidwebcam.core.stream.Avc
import app.androidwebcam.core.stream.EncodedFrame
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.stream.VideoConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class StreamAndOrientationTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0x2B)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21)

    @Test
    fun splitsAnnexBWithThreeAndFourByteStartCodes() {
        val stream = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr
        val nals = Avc.splitAnnexB(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
        assertArrayEquals(idr, nals[2])
    }

    @Test
    fun convertsAccessUnitsAndBuildsDecoderConfig() {
        val aud = byteArrayOf(0x09, 0xF0.toByte())
        val au = Avc.convertAccessUnit(byteArrayOf(0, 0, 0, 1) + aud + byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr)
        assertTrue(au.isKey)
        assertArrayEquals(sps, au.sps)
        assertArrayEquals(pps, au.pps)
        assertArrayEquals(byteArrayOf(0, 0, 0, idr.size.toByte()) + idr, au.avcc)

        assertEquals("avc1.64001f", Avc.codecString(sps))
        val record = Avc.decoderConfigurationRecord(sps, pps)
        assertEquals(1, record[0].toInt())
        assertEquals(0x64, record[1].toInt())
        assertEquals(0xFF.toByte(), record[4])
        assertEquals(0xE1.toByte(), record[5])
        assertEquals(sps.size, (record[6].toInt() shl 8) or record[7].toInt())
        // High profile records carry the chroma/bit-depth extension (4 bytes).
        assertEquals(6 + 2 + sps.size + 1 + 2 + pps.size + 4, record.size)
    }

    @Test
    fun hubGatesOnKeyframesAndRecoversFromSlowViewers() {
        val keyRequests = AtomicInteger()
        val demand = ArrayList<Boolean>()
        val hub = StreamHub(object : StreamHub.Listener {
            override fun onKeyframeNeeded() { keyRequests.incrementAndGet() }
            override fun onVideoDemandChanged(active: Boolean) { demand.add(active) }
        }, queueCapacity = 4)
        val cfg = VideoConfig("avc1.64001f", 1280, 720, ByteArray(4), generation = 1)
        hub.publishConfig(cfg)
        val sub = hub.subscribeVideo()
        assertEquals(listOf(true), demand)
        assertTrue(keyRequests.get() >= 1)
        assertTrue(sub.next(10) is VideoConfig)

        hub.publishFrame(EncodedFrame(ByteArray(1), 1, isKey = false, generation = 1))
        assertNull("delta frames before the first keyframe are skipped", sub.next(10))
        hub.publishFrame(EncodedFrame(ByteArray(1), 2, isKey = true, generation = 1))
        hub.publishFrame(EncodedFrame(ByteArray(1), 3, isKey = false, generation = 1))
        assertEquals(2L, (sub.next(10) as EncodedFrame).timestampUs)
        assertEquals(3L, (sub.next(10) as EncodedFrame).timestampUs)

        // Frames from an old encoder generation are ignored.
        hub.publishFrame(EncodedFrame(ByteArray(1), 4, isKey = true, generation = 0))
        assertNull(sub.next(10))

        // Overflow: queue is flushed and the viewer waits for the next keyframe.
        repeat(6) { hub.publishFrame(EncodedFrame(ByteArray(1), 10L + it, isKey = it == 0, generation = 1)) }
        assertTrue(sub.droppedBursts >= 1)
        while (sub.next(10) != null) Unit
        hub.publishFrame(EncodedFrame(ByteArray(1), 20, isKey = false, generation = 1))
        assertNull(sub.next(10))
        hub.publishFrame(EncodedFrame(ByteArray(1), 21, isKey = true, generation = 1))
        assertEquals(21L, (sub.next(10) as EncodedFrame).timestampUs)

        sub.close()
        assertEquals(listOf(true, false), demand)
    }

    @Test
    fun orientationTrackerDebouncesAndIgnoresAmbiguousAngles() {
        val t = Orientation.Tracker(tolerance = 30, stableMillis = 800)
        assertEquals(0, t.onReading(5, 0))
        assertNull(t.onReading(-1, 10)) // flat on a table
        assertNull(t.onReading(50, 20)) // between portrait and landscape
        assertNull(t.onReading(88, 100))
        assertNull(t.onReading(92, 500))
        assertEquals(90, t.onReading(95, 900))
        assertEquals(90, t.current)
        assertNull(t.onReading(355, 1000))
        assertNull(t.onReading(80, 1200)) // back to current resets the candidate
        assertNull(t.onReading(2, 1300))
        assertEquals(0, t.onReading(358, 2200))
    }

    @Test
    fun rotationsNeverMirrorThePicture() {
        for (rotation in listOf(0, 90, 180, 270)) {
            // A pure rotation keeps orientation: the determinant of the 2x2 map is +1.
            val (ax, ay) = Orientation.rotateClipPoint(1.0, 0.0, rotation)
            val (bx, by) = Orientation.rotateClipPoint(0.0, 1.0, rotation)
            val det = ax * by - ay * bx
            assertEquals("rotation $rotation must not mirror", 1.0, det, 1e-9)
        }
        // Rotating content 90° clockwise moves what was on the left to the top.
        val (x, y) = Orientation.rotateClipPoint(-1.0, 0.0, 90)
        assertEquals(0, x.roundToInt())
        assertEquals(1, y.roundToInt())
    }

    @Test
    fun geometryHelpers() {
        val natural = Orientation.naturalContentSize(1920, 1080, 90)
        assertEquals(Orientation.Size(1080, 1920), natural)
        assertEquals(Orientation.Size(1920, 1080), natural.rotated(90))
        assertEquals(Orientation.Size(1080, 1920), natural.rotated(180))
        assertEquals(90, Orientation.streamRotationForDevice(84))
        assertEquals(0, Orientation.snapToRightAngle(359))
        val vp = Orientation.fitViewport(Orientation.Size(1080, 1920), Orientation.Size(1000, 1000))
        assertEquals(1000, vp[3])
        assertTrue(abs(vp[2] - 562) <= 1)
        assertFalse(Orientation.Size(1920, 1080).isPortrait)
    }
}
