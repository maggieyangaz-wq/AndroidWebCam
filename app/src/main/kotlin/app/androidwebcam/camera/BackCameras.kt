package app.androidwebcam.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import app.androidwebcam.Quality
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

enum class CameraKind { MAIN, ULTRA_WIDE, TELE, OTHER }

/** A rear-facing camera. Front cameras are deliberately never offered. */
data class BackCamera(
    val id: String,
    val kind: CameraKind,
    val horizontalFov: Int?,
    val sensorOrientation: Int,
    val hasFlash: Boolean,
    val zoomMin: Float,
    val zoomMax: Float,
    val activeArray: Rect?,
    val continuousVideoAf: Boolean,
)

object BackCameras {
    fun list(context: Context): List<BackCamera> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = try { manager.cameraIdList.toList() } catch (e: Exception) { return emptyList() }
        val result = ArrayList<BackCamera>()
        for (id in ids.sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))) {
            val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue // depth-only etc.
            val fov = horizontalFov(chars)
            val zoomRange: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            result.add(
                BackCamera(
                    id = id,
                    kind = CameraKind.OTHER,
                    horizontalFov = fov,
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                    zoomMin = zoomRange?.lower?.coerceAtLeast(1f) ?: 1f,
                    zoomMax = (zoomRange?.upper ?: maxDigital).coerceAtLeast(1f).coerceAtMost(10f),
                    activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                    continuousVideoAf = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes,
                ),
            )
        }
        return classify(result)
    }

    /** First "normal" field of view camera is the main one; wider ones are ultra-wide. */
    private fun classify(cameras: List<BackCamera>): List<BackCamera> {
        val mainIndex = cameras.indexOfFirst { (it.horizontalFov ?: 70) in 45..90 }.let { if (it < 0) 0 else it }
        return cameras.mapIndexed { i, c ->
            val fov = c.horizontalFov
            val kind = when {
                i == mainIndex -> CameraKind.MAIN
                fov != null && fov > 90 -> CameraKind.ULTRA_WIDE
                fov != null && fov < 40 -> CameraKind.TELE
                else -> CameraKind.OTHER
            }
            c.copy(kind = kind)
        }.sortedBy { if (it.kind == CameraKind.MAIN) 0 else 1 }
    }

    private fun horizontalFov(chars: CameraCharacteristics): Int? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f) return null
        val longSide = maxOf(sensor.width, sensor.height)
        return Math.toDegrees(2.0 * atan(longSide / (2.0 * focal))).roundToInt()
    }

    /** Picks a SurfaceTexture size close to the requested quality that can run at [fps]. */
    fun chooseCaptureSize(chars: CameraCharacteristics, quality: Quality, fps: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val frameBudgetNs = 1_000_000_000L / fps
        fun fastEnough(s: Size): Boolean {
            val d = map.getOutputMinFrameDuration(SurfaceTexture::class.java, s)
            return d == 0L || d <= frameBudgetNs
        }
        val wanted = quality.shortSide
        val wide = sizes.filter { abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.02 && fastEnough(it) }
        return wide.filter { minOf(it.width, it.height) >= wanted }.minByOrNull { it.width * it.height }
            ?: wide.maxByOrNull { it.width * it.height }
            ?: sizes.filter(::fastEnough).minByOrNull { abs(minOf(it.width, it.height) - wanted) }
            ?: sizes.first()
    }

    /** Encoder size in the capture's (landscape) aspect ratio for the requested quality. */
    fun encodedLandscapeSize(capture: Size, quality: Quality): Size {
        val longSide = maxOf(capture.width, capture.height)
        val shortSide = minOf(capture.width, capture.height)
        val h = minOf(quality.shortSide, shortSide)
        val w = ((h.toDouble() * longSide / shortSide) / 16.0).roundToInt() * 16
        return Size(w.coerceAtLeast(16), h - (h % 2))
    }

    /**
     * Prefers a range that reaches [fps] but may drop to half of it in low light
     * (brighter picture in dim rooms), then an exact range.
     */
    fun chooseFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        ranges.filter { it.upper == fps && it.lower >= fps / 2 }.minByOrNull { it.lower }?.let { return it }
        ranges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
        ranges.filter { it.upper >= fps }.minByOrNull { it.upper * 1000 - it.lower }?.let { return it }
        return ranges.maxByOrNull { it.upper }
    }
}
