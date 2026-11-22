package app.androidwebcam.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import app.androidwebcam.AndroidLogger
import app.androidwebcam.BuildConfig
import app.androidwebcam.R
import app.androidwebcam.StreamSettings
import app.androidwebcam.camera.BackCameras
import app.androidwebcam.core.Orientation
import app.androidwebcam.core.auth.AccessControl
import app.androidwebcam.core.auth.AiKeys
import app.androidwebcam.core.auth.SecurityEvent
import app.androidwebcam.core.server.WebCamServer
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.tls.IdentityKeyManager
import app.androidwebcam.encoder.VideoPipeline
import app.androidwebcam.net.LanNetworkMonitor
import app.androidwebcam.security.AiKeyStore
import app.androidwebcam.security.TlsIdentityStore
import java.net.BindException
import java.util.concurrent.Executors

/**
 * One streaming session: network policy, TLS identity, access control, HTTPS server and
 * the camera pipeline. Everything is torn down (and all sessions forgotten) on [stop].
 */
class StreamSession(
    private val context: Context,
    private val settings: StreamSettings,
    private val host: Host,
) : StreamHub.Listener {
    interface Host {
        fun onViewerCountChanged(viewers: Int, url: String?)
        fun onSecurityAlert(text: String)
        fun onFatalError()
    }

    private val logger = AndroidLogger
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "awc-session").apply { isDaemon = true } }
    private val identityStore = TlsIdentityStore(context, logger)
    private val aiKeyStore = AiKeyStore(context)
    @Volatile private var aiKeys: AiKeys? = null
    private var network: LanNetworkMonitor? = null
    private var keyManager: IdentityKeyManager? = null
    private var access: AccessControl? = null
    @Volatile private var server: WebCamServer? = null
    @Volatile private var pipeline: VideoPipeline? = null
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var stopped = false

    fun start() {
        StreamController.update { StreamState(phase = Phase.STARTING, port = settings.port) }
        worker.execute { startInternal() }
    }

    private fun startInternal() {
        try {
            val net = LanNetworkMonitor(context, logger) { endpoints -> onEndpointsChanged(endpoints) }
            network = net
            main.post { if (!stopped) net.start() }
            net.refresh()

            val identity = identityStore.obtain(net.endpoints.map { it.address })
            val km = IdentityKeyManager(identity)
            keyManager = km
            val ac = AccessControl(onEvent = { event -> main.post { onSecurityEvent(event) } })
            access = ac
            val keys = AiKeys(aiKeyStore.load(), onChange = { records ->
                aiKeyStore.save(records)
                StreamController.update { it.copy(aiKeys = records.toInfo()) }
            })
            aiKeys = keys
            val hub = StreamHub(this)

            val srv = WebCamServer(
                WebCamServer.Options(port = settings.port, appVersion = BuildConfig.VERSION_NAME),
                km, ac, hub, net,
                object : WebCamServer.Listener {
                    override fun onViewersChanged(viewers: List<WebCamServer.Viewer>) {
                        StreamController.update { it.copy(viewers = viewers) }
                        main.post { host.onViewerCountChanged(viewers.size, primaryUrl()) }
                    }

                    override fun onSnapshot(address: String, client: String) {
                        val record = SnapshotRecord(address, client, System.currentTimeMillis())
                        StreamController.update { it.copy(snapshotCount = it.snapshotCount + 1, lastSnapshot = record) }
                    }

                    override fun onAiKeyCreated(address: String) {
                        main.post { if (!stopped) alert(context.getString(R.string.alert_ai_key, address)) }
                    }
                },
                logger,
                aiKeys = keys,
            )
            // A previous session may still be releasing the port for a moment.
            var bound = false
            for (attempt in 1..6) {
                try {
                    srv.start()
                    bound = true
                    break
                } catch (e: BindException) {
                    if (stopped) return
                    Thread.sleep(300)
                }
            }
            if (!bound) {
                fail(ErrorKind.PORT_IN_USE, settings.port.toString())
                return
            }
            if (stopped) {
                srv.stop()
                return
            }
            server = srv

            val cameras = BackCameras.list(context)
            val camera = cameras.firstOrNull { it.id == settings.cameraId } ?: cameras.firstOrNull()
            if (camera == null) {
                fail(ErrorKind.NO_CAMERA, null)
                return
            }
            val initialRotation = if (settings.orientation == StreamSettings.ORIENTATION_AUTO) 0 else settings.orientation
            val p = VideoPipeline(context, camera, settings, hub, logger, pipelineListener)
            pipeline = p
            p.start(initialRotation)
            // Viewers that connected while the pipeline was being created.
            p.setVideoDemand(hub.videoViewers > 0)
            p.setMjpegDemand(hub.mjpegViewers > 0)
            val natural = p.naturalSize

            StreamController.update {
                // Never overwrite an error (or a stop) that happened while starting.
                if (it.phase != Phase.STARTING || stopped) return@update it
                it.copy(
                    phase = Phase.RUNNING,
                    port = srv.port,
                    endpoints = net.endpoints,
                    accessCode = ac.displayCode,
                    pairingToken = ac.issuePairingToken(),
                    fingerprint = identity.fingerprintSha256,
                    hardwareKey = identity.hardwareBacked,
                    maxViewers = 4,
                    streamFps = settings.fps,
                    previewAspect = natural.width.toFloat() / natural.height,
                    torchAvailable = camera.hasFlash,
                    torchOn = false,
                    zoomMin = camera.zoomMin,
                    zoomMax = camera.zoomMax,
                    zoom = 1f,
                    aiKeys = keys.list().toInfo(),
                )
            }
            main.post {
                if (stopped) return@post
                StreamController.attachSession(this)
                startOrientationTracking()
                schedulePairingRefresh()
                host.onViewerCountChanged(0, primaryUrl())
            }
        } catch (e: Exception) {
            logger.e("start failed", e)
            fail(ErrorKind.OTHER, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(kind: ErrorKind, detail: String?) {
        StreamController.update { StreamState(phase = Phase.ERROR, error = kind, errorDetail = detail, port = settings.port) }
        main.post { host.onFatalError() }
    }

    private val pipelineListener = object : VideoPipeline.Listener {
        override fun onStreamSize(width: Int, height: Int) {
            StreamController.update { it.copy(streamWidth = width, streamHeight = height) }
        }

        override fun onCameraRunning() {
            StreamController.update { it.copy(cameraBusy = false) }
        }

        override fun onCameraProblem(reason: String, recoverable: Boolean) {
            if (recoverable) {
                StreamController.update { it.copy(cameraBusy = true) }
            } else {
                fail(ErrorKind.CAMERA, reason)
            }
        }

        override fun onEncoderProblem(reason: String) = fail(ErrorKind.ENCODER, reason)
    }

    // StreamHub.Listener — called from server threads.
    override fun onKeyframeNeeded() { pipeline?.requestKeyframe() }
    override fun onVideoDemandChanged(active: Boolean) { pipeline?.setVideoDemand(active) }
    override fun onMjpegDemandChanged(active: Boolean) { pipeline?.setMjpegDemand(active) }

    private fun startOrientationTracking() {
        if (settings.orientation != StreamSettings.ORIENTATION_AUTO) return
        val tracker = Orientation.Tracker()
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val degrees = if (orientation == ORIENTATION_UNKNOWN) -1 else orientation
                tracker.onReading(degrees, SystemClock.elapsedRealtime())?.let { device ->
                    pipeline?.setRotation(Orientation.streamRotationForDevice(device))
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        }
    }

    private val refreshPairing = object : Runnable {
        override fun run() {
            if (stopped) return
            access?.let { ac -> StreamController.update { it.copy(pairingToken = ac.issuePairingToken()) } }
            main.postDelayed(this, PAIRING_REFRESH_MS)
        }
    }

    private fun schedulePairingRefresh() {
        main.removeCallbacks(refreshPairing)
        main.postDelayed(refreshPairing, PAIRING_REFRESH_MS)
    }

    private fun onSecurityEvent(event: SecurityEvent) {
        if (stopped) return
        val text = when (event) {
            is SecurityEvent.ViewerAuthorized -> {
                if (event.viaPairing) {
                    // The QR token was used: show a fresh one right away.
                    access?.let { ac -> StreamController.update { it.copy(pairingToken = ac.issuePairingToken()) } }
                    schedulePairingRefresh()
                    context.getString(R.string.alert_viewer_qr, event.address, event.client)
                } else {
                    context.getString(R.string.alert_viewer, event.address, event.client)
                }
            }
            is SecurityEvent.AddressLocked -> context.getString(R.string.alert_locked, event.address, event.seconds.toInt())
            is SecurityEvent.CodeRotatedAfterAttack -> {
                access?.let { ac -> StreamController.update { it.copy(accessCode = ac.displayCode, pairingToken = ac.issuePairingToken()) } }
                context.getString(R.string.alert_rotated)
            }
            is SecurityEvent.LoginFailed -> return
        }
        alert(text)
    }

    private fun alert(text: String) {
        StreamController.update { it.copy(alerts = (listOf(text) + it.alerts).take(5)) }
        host.onSecurityAlert(text)
    }

    private fun List<AiKeys.Record>.toInfo() = map { AiKeyInfo(it.address, it.createdAt, it.lastUsedAt) }

    private fun onEndpointsChanged(endpoints: List<LanNetworkMonitor.Endpoint>) {
        if (stopped) return
        StreamController.update { it.copy(endpoints = endpoints) }
        main.post { if (!stopped) host.onViewerCountChanged(server?.currentViewers()?.size ?: 0, primaryUrl()) }
        val km = keyManager ?: return
        try {
            worker.execute {
                if (stopped) return@execute
                // New LAN address (e.g. another Wi-Fi): re-issue the certificate with the same hardware key.
                val refreshed = identityStore.refreshFor(km.identity, endpoints.map { it.address }) ?: return@execute
                km.identity = refreshed
                StreamController.update { it.copy(fingerprint = refreshed.fingerprintSha256, hardwareKey = refreshed.hardwareBacked) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // session is shutting down
        }
    }

    private fun primaryUrl(): String? {
        val endpoint = StreamController.state.value.endpoints.firstOrNull() ?: return null
        return "https://${endpoint.address.hostAddress}:${server?.port ?: settings.port}"
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        pipeline?.attachPreview(surface, width, height)
    }

    fun detachPreview() {
        pipeline?.detachPreview()
    }

    fun setTorch(on: Boolean) {
        pipeline?.setTorch(on)
        StreamController.update { it.copy(torchOn = on && it.torchAvailable) }
    }

    fun setZoom(ratio: Float) {
        pipeline?.setZoom(ratio)
        StreamController.update { it.copy(zoom = ratio.coerceIn(it.zoomMin, it.zoomMax)) }
    }

    fun disconnectAllAndRotateCode() {
        val ac = access ?: return
        ac.revokeAll()
        aiKeys?.revokeAll() // "disconnect all" also ends AI access
        server?.disconnectAllViewers()
        StreamController.update { it.copy(accessCode = ac.displayCode, pairingToken = ac.issuePairingToken()) }
        schedulePairingRefresh()
    }

    fun revokeAiKeys() {
        aiKeys?.revokeAll()
    }

    /** Stops everything; safe to call from the main thread. */
    fun stop() {
        if (stopped) return
        stopped = true
        StreamController.attachSession(null)
        main.removeCallbacks(refreshPairing)
        orientationListener?.disable()
        orientationListener = null
        network?.stop()
        worker.execute {
            try { server?.stop() } catch (_: Exception) {}
            try { pipeline?.stop() } catch (_: Exception) {}
            access?.revokeAll()
            server = null
            pipeline = null
        }
        worker.shutdown()
    }

    private companion object {
        const val PAIRING_REFRESH_MS = 4 * 60_000L
    }
}
