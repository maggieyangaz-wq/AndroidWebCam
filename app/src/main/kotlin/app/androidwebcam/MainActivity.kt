package app.androidwebcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import app.androidwebcam.camera.BackCameras
import app.androidwebcam.service.ErrorKind
import app.androidwebcam.service.Phase
import app.androidwebcam.service.StreamController
import app.androidwebcam.service.StreamState
import app.androidwebcam.service.StreamingService
import app.androidwebcam.ui.AppTheme
import app.androidwebcam.ui.MainScreen

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private var batteryOk by mutableStateOf(true)

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
        setContent {
            var settings by remember { mutableStateOf(settingsStore.load()) }
            AppTheme {
                MainScreen(
                    settings = settings,
                    cameras = cameras,
                    onSettingsChange = {
                        settings = it
                        settingsStore.save(it)
                    },
                    batteryOk = batteryOk,
                    onRequestBatteryExemption = ::requestBatteryExemption,
                    onStart = ::startStreaming,
                    onStop = { StreamingService.stop(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
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
