package app.androidwebcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidwebcam.camera.BackCameras
import app.androidwebcam.core.cloud.CloudInstructions
import app.androidwebcam.security.CloudAgentStore
import app.androidwebcam.security.TlsIdentityStore
import app.androidwebcam.service.ErrorKind
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamState
import app.androidwebcam.service.StreamingService
import app.androidwebcam.ui.AppTheme
import app.androidwebcam.ui.BlackoutScreen
import app.androidwebcam.ui.MainScreen

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private var batteryOk by mutableStateOf(true)
    private var phonePin by mutableStateOf<String?>(null)

    /**
     * Black-screen standby: the display stays on (lowest brightness, nothing drawn), so systems
     * like OnePlus/OxygenOS never treat the app as idle background work and streaming continues.
     */
    var blackout by mutableStateOf(false)
        private set

    @VisibleForTesting
    internal var autoBlackoutDelayMs = 60_000L

    private var keepAwakeActive = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val autoBlackout = Runnable {
        if (keepAwakeActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) enterBlackout()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCameraPermission()) {
            StreamingService.start(this)
        } else {
            StreamController.update { StreamState(phase = Phase.ERROR, error = ErrorKind.CAMERA_PERMISSION) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the access code and the live picture out of screenshots, screen recordings and Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Ignore taps that pass through another app's overlay (tapjacking the "Start" button).
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        settingsStore = SettingsStore(this)
        val cameras = BackCameras.list(this)
        // Extra background-running advice for systems that stop apps aggressively.
        val vendorTips = when (Build.MANUFACTURER.lowercase()) {
            "oneplus", "oppo", "realme" -> R.string.oneplus_tips
            "xiaomi", "redmi", "poco" -> R.string.miui_tips
            else -> null
        }
        setContent {
            var settings by remember { mutableStateOf(settingsStore.load()) }
            val state by StreamController.state.collectAsStateWithLifecycle()
            val cloudAgents by CloudAgentStore.get(this).agents.collectAsStateWithLifecycle()
            val streaming = state.phase == Phase.RUNNING
            LaunchedEffect(streaming, settings.keepAwake) { applyKeepAwake(streaming && settings.keepAwake) }
            BackHandler(enabled = blackout) { exitBlackout() }
            AppTheme {
                if (blackout) {
                    BlackoutScreen(onExit = ::exitBlackout)
                } else {
                    MainScreen(
                        settings = settings,
                        cameras = cameras,
                        onSettingsChange = {
                            settings = it
                            settingsStore.save(it)
                        },
                        batteryOk = batteryOk,
                        vendorTips = vendorTips,
                        onRequestBatteryExemption = ::requestBatteryExemption,
                        onStart = ::startStreaming,
                        onStop = { StreamingService.stop(this) },
                        onEnterBlackout = ::enterBlackout,
                        cloudAgents = cloudAgents,
                        phonePin = phonePin,
                        onCreateCloudAgent = { name ->
                            val credential = CloudAgentStore.get(this).create(name)
                            val chinese = ConfigurationCompat.getLocales(resources.configuration)[0]?.language == "zh"
                            CloudInstructions.text(settings.cloudRelay, credential, chinese)
                        },
                        onRevokeCloudAgent = { id -> CloudAgentStore.get(this).revoke(id) },
                        onCopyText = ::copyText,
                        onShareText = ::shareText,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        phonePin = TlsIdentityStore(this, AndroidLogger).savedPublicKeyPin()
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    override fun onStart() {
        super.onStart()
        if (keepAwakeActive) scheduleAutoBlackout()
    }

    override fun onStop() {
        super.onStop()
        // Coming back (e.g. after the power button) shows the normal screen again.
        exitBlackout()
        idleHandler.removeCallbacks(autoBlackout)
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(autoBlackout)
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (keepAwakeActive && !blackout) scheduleAutoBlackout()
    }

    /** While streaming, the screen never times out (OxygenOS caps the timeout at 30 minutes). */
    private fun applyKeepAwake(on: Boolean) {
        keepAwakeActive = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!blackout) scheduleAutoBlackout()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            idleHandler.removeCallbacks(autoBlackout)
            exitBlackout()
        }
    }

    private fun scheduleAutoBlackout() {
        idleHandler.removeCallbacks(autoBlackout)
        idleHandler.postDelayed(autoBlackout, autoBlackoutDelayMs)
    }

    fun enterBlackout() {
        if (blackout) return
        blackout = true
        idleHandler.removeCallbacks(autoBlackout)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF // lowest backlight
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun exitBlackout() {
        if (!blackout) return
        blackout = false
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (keepAwakeActive) scheduleAutoBlackout()
    }

    /** Secrets (cloud agent instructions) are marked sensitive, so the clipboard preview hides them. */
    private fun copyText(text: String) {
        val clip = android.content.ClipData.newPlainText(getString(R.string.app_name), text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        android.widget.Toast.makeText(this, R.string.toast_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startStreaming() {
        StreamController.clearError()
        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) StreamingService.start(this) else permissionRequest.launch(missing.toTypedArray())
    }

    @SuppressLint("BatteryLife") // explicit user action; needed on OxygenOS to keep streaming with the screen off
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }
}
