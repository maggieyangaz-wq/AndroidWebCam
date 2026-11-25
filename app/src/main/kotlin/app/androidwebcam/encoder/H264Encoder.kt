package app.androidwebcam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.androidwebcam.core.Logger
import app.androidwebcam.core.stream.Avc
import app.androidwebcam.core.stream.EncodedFrame
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.stream.VideoConfig
import java.io.IOException

/**
 * Hardware H.264 encoder fed through an input Surface. Output is converted to AVCC with
 * a separate decoder configuration (SPS/PPS) and published to the [StreamHub].
 */
class H264Encoder(
    desiredWidth: Int,
    desiredHeight: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val generation: Int,
    private val hub: StreamHub,
    private val logger: Logger,
    private val onFailure: (H264Encoder) -> Unit,
) {
    /** Actual encoded size; may be smaller than requested if the encoder has lower limits. */
    var width: Int = desiredWidth
        private set
    var height: Int = desiredHeight
        private set
    private val desired = desiredWidth to desiredHeight
    private val thread = HandlerThread("awc-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set
    @Volatile private var configPublished = false
    @Volatile private var released = false

    @Throws(IOException::class)
    fun start() {
        val plan = encodingPlan()
        if (plan.isEmpty()) {
            thread.quitSafely()
            throw IOException("no H.264 encoder supports ${desired.first}x${desired.second} or a smaller size")
        }
        var lastError: Exception? = null
        for ((info, size) in plan) {
            if (tryStart(info, size.first, size.second)) return
            lastError = lastStartError
        }
        thread.quitSafely()
        throw IOException("H.264 encoder could not be configured", lastError)
    }

    private var lastStartError: Exception? = null

    private fun tryStart(info: MediaCodecInfo, w: Int, h: Int): Boolean {
        val caps = info.getCapabilitiesForType(MIME)
        // High profile only where B-frames can be turned off (Android 10+): they add latency and
        // reorder frames. Before, the encoder's default profile (Baseline) is used.
        val highLevel = if (Build.VERSION.SDK_INT < 29) null else caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxOfOrNull { it.level }
        val attempts = listOfNotNull(highLevel?.let { MediaCodecInfo.CodecProfileLevel.AVCProfileHigh to it }, null to null)
        for ((profile, level) in attempts) {
            val c = MediaCodec.createByCodecName(info.name)
            try {
                val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // lowest latency, in-order output
                    if (profile != null && level != null) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        setInteger(MediaFormat.KEY_LEVEL, level)
                    }
                }
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                try {
                    c.start()
                } catch (e: Exception) {
                    surface.release()
                    throw e
                }
                inputSurface = surface
                codec = c
                width = w
                height = h
                logger.i("encoder ${info.name} ${w}x$h@$fps ${bitrate / 1000} kbps profile=${profile ?: "default"}")
                return true
            } catch (e: Exception) {
                lastStartError = e
                logger.w("encoder configuration failed (${info.name} ${w}x$h profile=$profile)", e)
                try { c.release() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Encoders (hardware first) and sizes to try, largest first. Some encoders cap the
     * frame size (or only accept landscape sizes up to a limit); then the largest smaller
     * size with the same aspect ratio is used instead of failing.
     */
    private fun encodingPlan(): List<Pair<MediaCodecInfo, Pair<Int, Int>>> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && (Build.VERSION.SDK_INT < 29 || !info.isAlias) && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }
        // Hardware first: a hardware encoder at a slightly smaller size beats software encoding
        // (heat, battery, frame rate). Each encoder is tried at its largest supported size.
        val encoders = all.filter { isHardware(it) } + all.filterNot { isHardware(it) }
        val candidates = EncoderSizes.candidates(desired.first, desired.second)
        val plan = ArrayList<Pair<MediaCodecInfo, Pair<Int, Int>>>()
        for (info in encoders) {
            val caps = try { info.getCapabilitiesForType(MIME).videoCapabilities } catch (e: Exception) { null } ?: continue
            val size = candidates.firstOrNull { caps.isSizeSupported(it.first, it.second) } ?: continue
            plan.add(info to size)
        }
        return plan
    }

    /** Before Android 10 there is no flag: Android's own software encoders are known by name. */
    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.") || name.endsWith(".sw"))
    }

    fun requestKeyframe() {
        if (released) return
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // codec is stopping
        }
    }

    fun release() {
        released = true
        val c = codec
        codec = null
        try { c?.stop() } catch (_: Exception) {}
        try { c?.release() } catch (_: Exception) {}
        if (this::inputSurface.isInitialized) inputSurface.release()
        thread.quitSafely()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onCodecConfig(data)
                    } else {
                        onAccessUnit(data, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // released concurrently
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            logger.w("encoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            if (!released && !e.isTransient) onFailure(this@H264Encoder)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (configPublished) return
            val sps = format.getByteBuffer("csd-0")?.let(::bytesOf)
            val pps = format.getByteBuffer("csd-1")?.let(::bytesOf)
            if (sps != null && pps != null) onCodecConfig(sps + pps)
        }
    }

    private fun bytesOf(buffer: java.nio.ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val out = ByteArray(copy.remaining())
        copy.get(out)
        return out
    }

    private fun onCodecConfig(annexB: ByteArray) {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in Avc.splitAnnexB(annexB)) {
            when (Avc.nalType(nal)) {
                Avc.NAL_SPS -> sps = nal
                Avc.NAL_PPS -> pps = nal
            }
        }
        if (sps != null && pps != null) publishConfig(sps, pps)
    }

    private fun publishConfig(sps: ByteArray, pps: ByteArray) {
        try {
            hub.publishConfig(VideoConfig(Avc.codecString(sps), width, height, Avc.decoderConfigurationRecord(sps, pps), generation))
            configPublished = true
        } catch (e: IllegalArgumentException) {
            logger.w("invalid parameter sets", e)
        }
    }

    private fun onAccessUnit(annexB: ByteArray, info: MediaCodec.BufferInfo) {
        val unit = Avc.convertAccessUnit(annexB)
        if (!configPublished && unit.sps != null && unit.pps != null) publishConfig(unit.sps!!, unit.pps!!)
        if (unit.avcc.isEmpty() || !configPublished) return
        val key = unit.isKey || info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        hub.publishFrame(EncodedFrame(unit.avcc, info.presentationTimeUs, key, generation))
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
