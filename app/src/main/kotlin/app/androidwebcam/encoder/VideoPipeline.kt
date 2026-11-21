package app.androidwebcam.encoder

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Surface
import app.androidwebcam.StreamSettings
import app.androidwebcam.camera.BackCamera
import app.androidwebcam.camera.BackCameras
import app.androidwebcam.camera.CameraSource
import app.androidwebcam.core.Logger
import app.androidwebcam.core.Orientation
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.gl.GlRenderer
import kotlin.math.roundToInt

/** Camera -> GL (rotation) -> H.264 encoder / MJPEG / preview. */
class VideoPipeline(
    private val context: Context,
    private val camera: BackCamera,
    private val settings: StreamSettings,
    private val hub: StreamHub,
    private val logger: Logger,
    private val listener: Listener,
) {
    interface Listener {
        fun onStreamSize(width: Int, height: Int)
        fun onCameraRunning()
        fun onCameraProblem(reason: String, recoverable: Boolean)
        fun onEncoderProblem(reason: String)
    }

    private val gl = GlRenderer(logger)
    private var cameraSource: CameraSource? = null
    @Volatile private var encoder: H264Encoder? = null
    private var mjpeg: MjpegEncoder? = null
    private var mjpegWanted = false
    private var generation = 0
    private var rotation = 0
    private lateinit var capture: Size
    private lateinit var natural: Orientation.Size

    /** Natural (portrait for phones) content size, used for the preview aspect ratio. */
    val naturalSize: Orientation.Size get() = natural

    fun start(initialRotation: Int) {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(camera.id)
        capture = BackCameras.chooseCaptureSize(chars, settings.quality, settings.fps)
        natural = Orientation.naturalContentSize(capture.width, capture.height, camera.sensorOrientation)
        rotation = Orientation.normalize(initialRotation)
        val cameraSurface = gl.start(capture.width, capture.height, natural)
        gl.streamRotation = rotation
        gl.runSync { createEncoder() }
        cameraSource = CameraSource(context, camera, BackCameras.chooseFpsRange(chars, settings.fps), logger, object : CameraSource.Listener {
            override fun onCameraRunning() = listener.onCameraRunning()
            override fun onCameraProblem(reason: String, recoverable: Boolean) = listener.onCameraProblem(reason, recoverable)
        }).also { it.open(cameraSurface) }
        logger.i("capture ${capture.width}x${capture.height}, sensor ${camera.sensorOrientation}°, natural $natural")
    }

    /** Encoded size: quality-scaled natural content, rotated for the viewer. */
    private fun outputSize(): Orientation.Size {
        val landscape = BackCameras.encodedLandscapeSize(capture, settings.quality)
        val naturalOut = if (natural.isPortrait) Orientation.Size(landscape.height, landscape.width) else Orientation.Size(landscape.width, landscape.height)
        return naturalOut.rotated(rotation)
    }

    /** GL thread only. */
    private fun createEncoder() {
        gl.setEncoderSurface(null, 0, 0)
        encoder?.release()
        encoder = null
        val size = outputSize()
        val enc = H264Encoder(size.width, size.height, settings.fps, settings.bitrate(), ++generation, hub, logger) { failed ->
            gl.handler.postDelayed({ if (encoder === failed) createEncoder() }, 500)
        }
        try {
            enc.start()
        } catch (e: Exception) {
            logger.e("encoder start failed", e)
            listener.onEncoderProblem(e.message ?: e.javaClass.simpleName)
            return
        }
        encoder = enc
        // The encoder may have picked a smaller size than requested (same aspect ratio).
        gl.setEncoderSurface(enc.inputSurface, enc.width, enc.height)
        enc.requestKeyframe()
        listener.onStreamSize(enc.width, enc.height)
        if (mjpegWanted) createMjpeg()
    }

    /** GL thread only. MJPEG is capped at 720p and 15 fps to keep bandwidth reasonable. */
    private fun createMjpeg() {
        gl.setMjpegSurface(null, 0, 0, 0)
        mjpeg?.release()
        mjpeg = null
        val out = outputSize()
        val scale = minOf(1.0, 720.0 / minOf(out.width, out.height))
        val w = ((out.width * scale) / 2).roundToInt() * 2
        val h = ((out.height * scale) / 2).roundToInt() * 2
        val m = try {
            MjpegEncoder(w, h, 70, hub, logger)
        } catch (e: Exception) {
            logger.w("MJPEG unavailable", e)
            return
        }
        mjpeg = m
        gl.setMjpegSurface(m.surface, w, h, minOf(settings.fps, 15))
    }

    fun setRotation(degrees: Int) {
        val target = Orientation.normalize(degrees)
        gl.handler.post {
            if (target == rotation) return@post
            val resize = (target - rotation + 360) % 180 != 0
            rotation = target
            gl.streamRotation = target
            if (resize) createEncoder() else encoder?.requestKeyframe()
        }
    }

    fun setVideoDemand(active: Boolean) {
        gl.encoderEnabled = active
        if (active) encoder?.requestKeyframe()
    }

    fun setMjpegDemand(active: Boolean) {
        gl.handler.post {
            mjpegWanted = active
            if (active && mjpeg == null) {
                createMjpeg()
            } else if (!active && mjpeg != null) {
                gl.setMjpegSurface(null, 0, 0, 0)
                mjpeg?.release()
                mjpeg = null
            }
        }
    }

    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) = gl.setPreviewSurface(surface, width, height)

    fun detachPreview() = gl.setPreviewSurface(null, 0, 0)

    fun setTorch(on: Boolean) = cameraSource?.setTorch(on)

    fun setZoom(ratio: Float) = cameraSource?.setZoom(ratio)

    fun stop() {
        cameraSource?.close() // stop the camera before its output surface goes away
        cameraSource = null
        try {
            gl.runSync {
                gl.setEncoderSurface(null, 0, 0)
                gl.setMjpegSurface(null, 0, 0, 0)
                encoder?.release()
                encoder = null
                mjpeg?.release()
                mjpeg = null
            }
        } catch (e: Exception) {
            logger.w("pipeline stop", e)
        }
        gl.release()
    }
}
