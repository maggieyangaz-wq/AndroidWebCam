package app.androidwebcam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val generation: Int,
    private val hub: StreamHub,
    private val logger: Logger,
    private val onFailure: (H264Encoder) -> Unit,
) {
    private val thread = HandlerThread("awc-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set
    @Volatile private var configPublished = false
    @Volatile private var released = false

    @Throws(IOException::class)
    fun start() {
        val info = selectEncoder() ?: throw IOException("no H.264 encoder supports ${width}x$height")
        val caps = info.getCapabilitiesForType(MIME)
        val highLevel = caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxOfOrNull { it.level }
        val attempts = listOfNotNull(highLevel?.let { MediaCodecInfo.CodecProfileLevel.AVCProfileHigh to it }, null to null)
        var lastError: Exception? = null
        for ((profile, level) in attempts) {
            val c = MediaCodec.createByCodecName(info.name)
            try {
                val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // lowest latency, in-order output
                    if (profile != null && level != null) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        setInteger(MediaFormat.KEY_LEVEL, level)
                    }
                }
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                logger.i("encoder ${info.name} ${width}x$height@$fps ${bitrate / 1000} kbps profile=${profile ?: "default"}")
                return
            } catch (e: Exception) {
                lastError = e
                logger.w("encoder configuration failed (profile=$profile)", e)
                try { c.release() } catch (_: Exception) {}
            }
        }
        thread.quitSafely()
        throw IOException("H.264 encoder could not be configured", lastError)
    }

    private fun selectEncoder(): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && !info.isAlias && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }
        fun usable(info: MediaCodecInfo): Boolean = try {
            info.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(width, height) == true
        } catch (e: Exception) {
            false
        }
        return all.filter { it.isHardwareAccelerated }.firstOrNull(::usable) ?: all.firstOrNull(::usable)
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
