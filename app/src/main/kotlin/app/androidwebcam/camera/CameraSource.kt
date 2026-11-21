package app.androidwebcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import app.androidwebcam.core.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Camera2 wrapper with a single output (the GL SurfaceTexture) — the most widely
 * supported stream configuration. Recovers automatically when another app
 * temporarily takes the camera.
 */
class CameraSource(
    context: Context,
    private val camera: BackCamera,
    private val fpsRange: Range<Int>?,
    private val logger: Logger,
    private val listener: Listener,
) {
    interface Listener {
        fun onCameraRunning()
        /** [recoverable]: the camera is retried automatically. */
        fun onCameraProblem(reason: String, recoverable: Boolean)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("awc-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var target: Surface? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private var retryDelayMs = 1000L
    @Volatile private var closed = false
    @Volatile private var torchOn = false
    @Volatile private var zoomRatio = 1f

    fun open(surface: Surface) {
        handler.post {
            target = surface
            openDevice()
        }
    }

    fun setTorch(on: Boolean) {
        torchOn = on && camera.hasFlash
        handler.post { applyRequest() }
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(camera.zoomMin, camera.zoomMax)
        handler.post { applyRequest() }
    }

    fun close() {
        closed = true
        val done = CountDownLatch(1)
        handler.post {
            availability?.let { manager.unregisterAvailabilityCallback(it) }
            availability = null
            handler.removeCallbacksAndMessages(RETRY_TOKEN)
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            session = null
            device = null
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission") // checked before the service starts
    private fun openDevice() {
        if (closed || device != null) return
        try {
            manager.openCamera(camera.id, executor, stateCallback)
        } catch (e: CameraAccessException) {
            logger.w("openCamera failed", e)
            listener.onCameraProblem(e.reason.toString(), recoverable = true)
            waitUntilAvailable()
        } catch (e: SecurityException) {
            listener.onCameraProblem("permission", recoverable = false)
        } catch (e: IllegalArgumentException) {
            listener.onCameraProblem("unknown camera", recoverable = false)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            if (closed) {
                d.close()
                return
            }
            device = d
            retryDelayMs = 1000L
            createSession(d)
        }

        override fun onDisconnected(d: CameraDevice) {
            d.close()
            if (device === d) {
                device = null
                session = null
            }
            if (!closed) {
                listener.onCameraProblem("in use", recoverable = true)
                waitUntilAvailable()
            }
        }

        override fun onError(d: CameraDevice, error: Int) {
            d.close()
            if (device === d) {
                device = null
                session = null
            }
            if (closed) return
            listener.onCameraProblem("error $error", recoverable = true)
            if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) waitUntilAvailable() else retryLater()
        }
    }

    private fun createSession(d: CameraDevice) {
        val surface = target ?: return
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed || device !== d) {
                            s.close()
                            return
                        }
                        session = s
                        if (applyRequest()) listener.onCameraRunning()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        listener.onCameraProblem("configuration failed", recoverable = true)
                        retryLater()
                    }
                },
            )
            d.createCaptureSession(config)
        } catch (e: Exception) {
            logger.w("createCaptureSession failed", e)
            listener.onCameraProblem("session", recoverable = true)
            retryLater()
        }
    }

    private fun applyRequest(): Boolean {
        val d = device ?: return false
        val s = session ?: return false
        val surface = target ?: return false
        return try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (camera.continuousVideoAf) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            // Electronic stabilization adds latency and crops the picture; keep it off.
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            b.set(CaptureRequest.FLASH_MODE, if (torchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            applyZoom(b)
            s.setRepeatingRequest(b.build(), null, handler)
            true
        } catch (e: Exception) {
            logger.w("setRepeatingRequest failed", e)
            false
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        val ratio = zoomRatio
        if (ratio <= 1f && camera.zoomMax <= 1f) return // no zoom support: leave the request untouched
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            return
        }
        val active = camera.activeArray ?: return
        if (ratio <= 1f) return
        val w = (active.width() / ratio).toInt()
        val h = (active.height() / ratio).toInt()
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + w, top + h))
    }

    private fun retryLater() {
        if (closed) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
        handler.postDelayed({ openDevice() }, RETRY_TOKEN, delay)
    }

    /** Re-opens the camera as soon as the other app releases it. */
    private fun waitUntilAvailable() {
        if (closed || availability != null) return
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                if (cameraId != camera.id || closed) return
                manager.unregisterAvailabilityCallback(this)
                availability = null
                // Give the other app a moment to fully let go before reopening.
                handler.postDelayed({ openDevice() }, RETRY_TOKEN, 700)
            }
        }
        availability = callback
        manager.registerAvailabilityCallback(executor, callback)
    }

    private companion object {
        val RETRY_TOKEN = Any()
    }
}
