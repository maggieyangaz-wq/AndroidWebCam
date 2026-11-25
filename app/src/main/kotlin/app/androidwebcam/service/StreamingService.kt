package app.androidwebcam.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androidwebcam.AndroidLogger
import app.androidwebcam.SettingsStore

/**
 * Foreground service (type "camera") that owns the streaming session, so streaming keeps
 * running with the screen off. It is never restarted automatically and never starts at boot.
 */
class StreamingService : Service(), StreamSession.Host {
    private var session: StreamSession? = null
    private lateinit var notifications: Notifications
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = ArrayList<WifiManager.WifiLock>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = Notifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (session == null) {
                    acquireLocks()
                    session = StreamSession(this, SettingsStore(this).load(), this).also { it.start() }
                }
            }
            else -> shutdown() // ACTION_STOP, or an unexpected restart
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            StreamController.update { StreamState(phase = Phase.ERROR, error = ErrorKind.CAMERA_PERMISSION) }
            return false
        }
        return try {
            val notification = notifications.streaming(0, null)
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(Notifications.ID_STREAMING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(Notifications.ID_STREAMING, notification)
            }
            true
        } catch (e: Exception) {
            AndroidLogger.e("startForeground failed", e)
            StreamController.update { StreamState(phase = Phase.ERROR, error = ErrorKind.OTHER, errorDetail = e.message) }
            false
        }
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF still keeps Wi-Fi awake on Android 10-13.
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidWebCam:stream").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val modes = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            Build.VERSION.SDK_INT >= 29 -> listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            else -> listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        }
        for (mode in modes) {
            try {
                wifiLocks.add(wifi.createWifiLock(mode, "AndroidWebCam:$mode").apply {
                    setReferenceCounted(false)
                    acquire()
                })
            } catch (e: Exception) {
                AndroidLogger.w("wifi lock $mode unavailable", e)
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        for (lock in wifiLocks) if (lock.isHeld) lock.release()
        wifiLocks.clear()
    }

    private fun shutdown() {
        session?.stop()
        session = null
        releaseLocks()
        StreamController.update { if (it.phase == Phase.ERROR) it else StreamState() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        releaseLocks()
        super.onDestroy()
    }

    // StreamSession.Host
    override fun onViewerCountChanged(viewers: Int, url: String?) {
        if (session != null) {
            val cloud = StreamController.state.value.cloudStatus == app.androidwebcam.core.cloud.RelayClient.Status.CONNECTED
            notifications.update(Notifications.ID_STREAMING, notifications.streaming(viewers, url, cloud))
        }
    }

    override fun onSecurityAlert(text: String) = notifications.securityAlert(text)

    override fun onFatalError() = shutdown()

    companion object {
        const val ACTION_START = "app.androidwebcam.action.START"
        const val ACTION_STOP = "app.androidwebcam.action.STOP"
        private const val MAX_WAKE_LOCK_MS = 24 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }
}
