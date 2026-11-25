package app.androidwebcam.encoder

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.graphics.createBitmap
import app.androidwebcam.core.Logger
import app.androidwebcam.core.stream.StreamHub
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Compatibility stream for browsers without WebCodecs: the GL renderer draws into an
 * RGBA ImageReader (already rotated, never mirrored) and frames are JPEG-compressed.
 * Only runs while at least one MJPEG viewer is connected.
 */
class MjpegEncoder(
    val width: Int,
    val height: Int,
    private val quality: Int,
    private val hub: StreamHub,
    private val logger: Logger,
) {
    private val thread = HandlerThread("awc-mjpeg").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader = if (Build.VERSION.SDK_INT >= 29) {
        ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
    } else {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    }
    private val bitmap: Bitmap = createBitmap(width, height)
    private val row = ByteArray(width * 4)
    private var packed: ByteBuffer? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)
    @Volatile private var released = false

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ encodeLatest(it) }, handler)
    }

    private fun encodeLatest(r: ImageReader) {
        if (released) return
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            if (rowStride == width * 4) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val dst = packed ?: ByteBuffer.allocateDirect(width * height * 4).also { packed = it }
                dst.clear()
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    buffer.get(row, 0, row.size)
                    dst.put(row)
                }
                dst.flip()
                bitmap.copyPixelsFromBuffer(dst)
            }
            jpeg.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
            hub.publishJpeg(jpeg.toByteArray())
        } catch (e: RuntimeException) {
            logger.w("MJPEG frame failed", e)
        } finally {
            image.close()
        }
    }

    fun release() {
        released = true
        handler.post {
            try { reader.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            bitmap.recycle()
        }
        thread.quitSafely()
    }
}
