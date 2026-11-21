package app.androidwebcam.core

/**
 * Pure orientation / geometry helpers shared by the Android pipeline and tests.
 *
 * Conventions:
 *  - All rotations are clockwise degrees in {0, 90, 180, 270}.
 *  - The camera framework already rotates SurfaceTexture content by the sensor
 *    orientation (back cameras are never mirrored), so content sampled through the
 *    SurfaceTexture transform is upright when the phone is in its natural (portrait)
 *    orientation. The stream then only needs the *device* rotation on top.
 *  - Only rotations are ever applied. A rotation can never mirror the picture, which
 *    is how "left stays left" is guaranteed for people in the frame.
 */
object Orientation {
    data class Size(val width: Int, val height: Int) {
        val isPortrait: Boolean get() = height > width
        fun rotated(degrees: Int): Size = if (normalize(degrees) % 180 == 90) Size(height, width) else this
        override fun toString(): String = "${width}x$height"
    }

    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Size of the SurfaceTexture content after the framework applied the sensor rotation. */
    fun naturalContentSize(bufferWidth: Int, bufferHeight: Int, sensorOrientation: Int): Size =
        Size(bufferWidth, bufferHeight).rotated(sensorOrientation)

    /**
     * Clockwise rotation to apply to natural content so that the picture is upright
     * for viewers while the device is physically rotated by [deviceOrientation]
     * (OrientationEventListener convention: 90 = device rotated clockwise).
     * Equivalent to the CaptureRequest.JPEG_ORIENTATION formula for back cameras.
     */
    fun streamRotationForDevice(deviceOrientation: Int): Int = snapToRightAngle(deviceOrientation)

    fun snapToRightAngle(degrees: Int): Int = normalize(((normalize(degrees) + 45) / 90) * 90)

    /** Smallest angular distance between two angles in degrees. */
    fun angularDistance(a: Int, b: Int): Int {
        val d = normalize(a - b)
        return if (d > 180) 360 - d else d
    }

    /**
     * Debounced orientation tracking with hysteresis: readings must be within
     * [tolerance] degrees of a right angle, and a new orientation must be stable
     * for [stableMillis] before it is accepted. The first reading is applied at once.
     */
    class Tracker(
        private val tolerance: Int = 30,
        private val stableMillis: Long = 800,
    ) {
        var current: Int = -1
            private set
        private var candidate = -1
        private var candidateSince = 0L

        /** Returns the new orientation when it changes, otherwise null. */
        fun onReading(degrees: Int, nowMillis: Long): Int? {
            if (degrees < 0) return null // ORIENTATION_UNKNOWN (device flat)
            val nearest = snapToRightAngle(degrees)
            if (angularDistance(degrees, nearest) > tolerance) return null
            if (current == -1) {
                current = nearest
                return nearest
            }
            if (nearest == current) {
                candidate = -1
                return null
            }
            if (candidate != nearest) {
                candidate = nearest
                candidateSince = nowMillis
                return null
            }
            if (nowMillis - candidateSince >= stableMillis) {
                current = nearest
                candidate = -1
                return nearest
            }
            return null
        }
    }

    /** Centered viewport (x, y, w, h) that fits [content] inside [view] without distortion. */
    fun fitViewport(content: Size, view: Size): IntArray {
        if (content.width <= 0 || content.height <= 0 || view.width <= 0 || view.height <= 0) {
            return intArrayOf(0, 0, view.width.coerceAtLeast(0), view.height.coerceAtLeast(0))
        }
        val scale = minOf(view.width.toDouble() / content.width, view.height.toDouble() / content.height)
        val w = (content.width * scale).toInt().coerceIn(1, view.width)
        val h = (content.height * scale).toInt().coerceIn(1, view.height)
        return intArrayOf((view.width - w) / 2, (view.height - h) / 2, w, h)
    }

    /**
     * 2D model of the GL vertex rotation used by the renderer: rotating clip-space
     * positions by -[rotation] degrees around Z turns the picture clockwise.
     * Returns the (x, y) clip coordinate where clip point (x, y) ends up.
     */
    fun rotateClipPoint(x: Double, y: Double, rotation: Int): Pair<Double, Double> {
        val rad = Math.toRadians(-normalize(rotation).toDouble())
        val c = Math.cos(rad)
        val s = Math.sin(rad)
        return Pair(c * x - s * y, s * x + c * y)
    }
}
