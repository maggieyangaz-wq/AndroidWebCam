package app.androidwebcam.service

import android.annotation.SuppressLint
import android.view.Surface
import app.androidwebcam.core.server.WebCamServer
import app.androidwebcam.net.LanNetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, STARTING, RUNNING, ERROR }

enum class ErrorKind { CAMERA_PERMISSION, PORT_IN_USE, NO_CAMERA, CAMERA, ENCODER, OTHER }

data class StreamState(
    val phase: Phase = Phase.IDLE,
    val error: ErrorKind? = null,
    val errorDetail: String? = null,
    val cameraBusy: Boolean = false,
    val port: Int = 0,
    val endpoints: List<LanNetworkMonitor.Endpoint> = emptyList(),
    val accessCode: String? = null,
    val pairingToken: String? = null,
    val fingerprint: String? = null,
    val hardwareKey: Boolean = false,
    val viewers: List<WebCamServer.Viewer> = emptyList(),
    val maxViewers: Int = 4,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val streamFps: Int = 0,
    val previewAspect: Float = 9f / 16f,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val zoom: Float = 1f,
    val alerts: List<String> = emptyList(),
)

/**
 * Process-wide bridge between the UI and the running [StreamSession]. Holds the UI
 * state and the current preview surface (which may appear before or after streaming starts).
 */
object StreamController {
    private val mutableState = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    // Cleared in StreamSession.stop(), so it never outlives the foreground service.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var session: StreamSession? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    internal fun update(transform: (StreamState) -> StreamState) = mutableState.update(transform)

    internal fun attachSession(s: StreamSession?) {
        session = s
        if (s != null) currentPreview()?.let { (surface, w, h) -> s.attachPreview(surface, w, h) }
    }

    @Synchronized
    private fun currentPreview(): Triple<Surface, Int, Int>? =
        previewSurface?.takeIf { it.isValid }?.let { Triple(it, previewWidth, previewHeight) }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        synchronized(this) {
            previewSurface = surface
            previewWidth = width
            previewHeight = height
        }
        session?.attachPreview(surface, width, height)
    }

    fun detachPreview() {
        synchronized(this) { previewSurface = null }
        session?.detachPreview()
    }

    fun setTorch(on: Boolean) { session?.setTorch(on) }

    fun setZoom(ratio: Float) { session?.setZoom(ratio) }

    fun disconnectAllAndRotateCode() { session?.disconnectAllAndRotateCode() }

    fun clearError() = update { if (it.phase == Phase.ERROR) StreamState() else it }
}
