package app.androidwebcam

import android.content.Context
import androidx.core.content.edit

enum class Quality(val label: String, val shortSide: Int, val bitrateAt30Fps: Int) {
    P540("540p", 540, 1_800_000),
    P720("720p", 720, 3_500_000),
    P1080("1080p", 1080, 6_500_000),
}

/**
 * User settings. [orientation] is the assumed device rotation in degrees
 * (0/90/180/270, OrientationEventListener convention) or [ORIENTATION_AUTO].
 */
data class StreamSettings(
    val cameraId: String? = null,
    val quality: Quality = Quality.P720,
    val fps: Int = 30,
    val orientation: Int = ORIENTATION_AUTO,
    val port: Int = DEFAULT_PORT,
    /**
     * Keep the screen on while streaming (black, lowest brightness after a minute without touch).
     * Systems such as OnePlus/OxygenOS end background apps after the screen has been off for a while.
     */
    val keepAwake: Boolean = true,
) {
    fun bitrate(): Int = (quality.bitrateAt30Fps * maxOf(0.6, fps / 30.0)).toInt()

    companion object {
        const val ORIENTATION_AUTO = -1
        const val DEFAULT_PORT = 8443
        val FPS_CHOICES = listOf(15, 24, 30)
        val ORIENTATION_CHOICES = listOf(ORIENTATION_AUTO, 0, 270, 90, 180)
        fun isValidPort(port: Int) = port in 1024..65535
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("awc_settings", Context.MODE_PRIVATE)

    fun load(): StreamSettings {
        val defaults = StreamSettings()
        return StreamSettings(
            cameraId = prefs.getString("camera", null),
            quality = runCatching { Quality.valueOf(prefs.getString("quality", defaults.quality.name)!!) }.getOrDefault(defaults.quality),
            fps = prefs.getInt("fps", defaults.fps).takeIf { it in StreamSettings.FPS_CHOICES } ?: defaults.fps,
            orientation = prefs.getInt("orientation", defaults.orientation).takeIf { it in StreamSettings.ORIENTATION_CHOICES } ?: defaults.orientation,
            port = prefs.getInt("port", defaults.port).takeIf { StreamSettings.isValidPort(it) } ?: defaults.port,
            keepAwake = prefs.getBoolean("keep_awake", defaults.keepAwake),
        )
    }

    fun save(settings: StreamSettings) {
        prefs.edit {
            putString("camera", settings.cameraId)
            putString("quality", settings.quality.name)
            putInt("fps", settings.fps)
            putInt("orientation", settings.orientation)
            putInt("port", settings.port)
            putBoolean("keep_awake", settings.keepAwake)
        }
    }
}
