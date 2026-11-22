package app.androidwebcam

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Starts [MainActivity] and waits until it is in the foreground, with a caller-chosen timeout.
 * (Instrumentation.startActivitySync gives up after 45 s, which a software-emulated device that
 * has just booted can exceed.)
 */
internal fun launchMainActivity(timeoutMs: Long): MainActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
    try {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val activity = monitor.waitForActivityWithTimeout(timeoutMs) as? MainActivity
            ?: throw AssertionError("MainActivity did not start within $timeoutMs ms")
        while (SystemClock.uptimeMillis() < deadline) {
            var resumed = false
            instrumentation.runOnMainSync { resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            if (resumed) return activity
            Thread.sleep(100)
        }
        throw AssertionError("MainActivity did not reach the foreground within $timeoutMs ms")
    } finally {
        instrumentation.removeMonitor(monitor)
    }
}
