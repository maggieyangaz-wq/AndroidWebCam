package app.androidwebcam

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Starts [MainActivity] and waits until it is in the foreground, with a caller-chosen timeout.
 * (Instrumentation.startActivitySync gives up after 45 s, which a software-emulated device that
 * has just booted can exceed.) The activity of an earlier test may still be finishing when this
 * one starts (and on Android 6 an activity monitor can report that old one), so this waits for a
 * resumed instance that is not finishing.
 */
internal fun launchMainActivity(timeoutMs: Long): MainActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        var resumed: MainActivity? = null
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull { !it.isFinishing }
        }
        resumed?.let { return it }
        Thread.sleep(100)
    }
    throw AssertionError("MainActivity did not reach the foreground within $timeoutMs ms")
}
