package app.androidwebcam.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import app.androidwebcam.core.Logger
import app.androidwebcam.core.Orientation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One camera stream in, up to three outputs: the H.264 encoder, the MJPEG ImageReader
 * and the on-screen preview. Rotation happens here (pure rotation matrices only), so
 * every output is upright and never mirrored.
 *
 * The SurfaceTexture transform already contains the sensor-orientation rotation applied
 * by the camera framework, so sampled content is upright for the phone's natural
 * (portrait) orientation; [streamRotation] adds the physical device rotation.
 */
class GlRenderer(private val logger: Logger) {
    private class Target(val surface: Surface, val egl: EGLSurface, val width: Int, val height: Int)

    private val thread = HandlerThread("awc-gl").apply { start() }
    val handler = Handler(thread.looper)

    private var egl: EglCore? = null
    private var program: OesProgram? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var natural = Orientation.Size(1, 1)
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    private var encoderTarget: Target? = null
    private var mjpegTarget: Target? = null
    private var previewTarget: Target? = null
    private var mjpegIntervalNs = 0L
    private var lastMjpegNs = 0L
    private var released = false

    /** Clockwise rotation applied to the streamed picture (0/90/180/270). */
    @Volatile var streamRotation = 0

    /** Encoding is skipped while nobody watches the H.264 stream (saves power). */
    @Volatile var encoderEnabled = false

    fun <T> runSync(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "GL thread did not respond" }
        return result!!.getOrThrow()
    }

    /** Creates the GL context and the camera input surface. */
    fun start(bufferWidth: Int, bufferHeight: Int, naturalSize: Orientation.Size): Surface = runSync {
        val core = EglCore()
        egl = core
        offscreen = core.createOffscreenSurface()
        makeIdleCurrent()
        val p = OesProgram()
        program = p
        texture = p.createTexture()
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        natural = naturalSize
        Surface(st).also { cameraSurface = it }
    }

    fun setEncoderSurface(surface: Surface?, width: Int, height: Int) = runSync {
        encoderTarget = replace(encoderTarget, surface, width, height)
    }

    fun setMjpegSurface(surface: Surface?, width: Int, height: Int, fps: Int) = runSync {
        mjpegTarget = replace(mjpegTarget, surface, width, height)
        mjpegIntervalNs = if (fps > 0) 1_000_000_000L / fps else 0L
        lastMjpegNs = 0L
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) = runSync {
        previewTarget = replace(previewTarget, surface, width, height)
    }

    private fun replace(old: Target?, surface: Surface?, width: Int, height: Int): Target? {
        val core = egl ?: return null
        if (old != null) {
            makeIdleCurrent()
            core.releaseSurface(old.egl)
        }
        if (surface == null || !surface.isValid || width <= 0 || height <= 0 || released) return null
        return try {
            Target(surface, core.createWindowSurface(surface), width, height)
        } catch (e: RuntimeException) {
            logger.w("cannot render to surface", e)
            null
        }
    }

    private fun makeIdleCurrent() {
        val core = egl ?: return
        if (offscreen == EGL14.EGL_NO_SURFACE || !core.makeCurrent(offscreen)) {
            core.makeCurrentSurfaceless()
        }
    }

    private fun onFrameAvailable() {
        if (released) return
        val core = egl ?: return
        val st = surfaceTexture ?: return
        try {
            makeIdleCurrent()
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val timestamp = st.timestamp
            val rotation = streamRotation

            encoderTarget?.let { t ->
                if (encoderEnabled) {
                    draw(core, t, rotation, letterbox = false)
                    core.setPresentationTime(t.egl, timestamp)
                    if (!core.swapBuffers(t.egl)) logger.w("encoder swap failed")
                }
            }
            mjpegTarget?.let { t ->
                if (timestamp - lastMjpegNs >= mjpegIntervalNs) {
                    lastMjpegNs = timestamp
                    draw(core, t, rotation, letterbox = false)
                    core.swapBuffers(t.egl)
                }
            }
            previewTarget?.let { t ->
                // The preview is a "window" through the phone (activity is portrait-locked): no extra rotation.
                draw(core, t, 0, letterbox = true)
                if (!core.swapBuffers(t.egl)) {
                    makeIdleCurrent()
                    core.releaseSurface(t.egl)
                    previewTarget = null
                }
            }
        } catch (e: RuntimeException) {
            logger.w("frame rendering failed", e)
        }
    }

    private fun draw(core: EglCore, t: Target, rotation: Int, letterbox: Boolean) {
        core.makeCurrent(t.egl)
        GLES20.glViewport(0, 0, t.width, t.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (letterbox) {
            val vp = Orientation.fitViewport(natural.rotated(rotation), Orientation.Size(t.width, t.height))
            GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        }
        // Rotating clip-space vertices by -rotation turns the picture clockwise (see Orientation.rotateClipPoint).
        Matrix.setRotateM(mvp, 0, -rotation.toFloat(), 0f, 0f, 1f)
        program?.draw(mvp, texMatrix, texture)
    }

    fun release() {
        try {
            runSync {
                released = true
                val core = egl
                if (core != null) {
                    makeIdleCurrent()
                    for (t in listOfNotNull(encoderTarget, mjpegTarget, previewTarget)) core.releaseSurface(t.egl)
                    encoderTarget = null
                    mjpegTarget = null
                    previewTarget = null
                    surfaceTexture?.setOnFrameAvailableListener(null)
                    surfaceTexture?.release()
                    cameraSurface?.release()
                    program?.release()
                    if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                    core.releaseSurface(offscreen)
                    core.release()
                }
                egl = null
            }
        } catch (e: Exception) {
            logger.w("GL release failed", e)
        } finally {
            thread.quitSafely()
        }
    }
}
