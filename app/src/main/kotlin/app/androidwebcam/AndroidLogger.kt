package app.androidwebcam

import android.util.Log
import app.androidwebcam.core.Logger

/** Logs only in debug builds; release builds additionally strip all Log calls with R8. */
object AndroidLogger : Logger {
    private const val TAG = "AndroidWebCam"
    override fun d(message: String) { if (BuildConfig.DEBUG) Log.d(TAG, message) }
    override fun i(message: String) { if (BuildConfig.DEBUG) Log.i(TAG, message) }
    override fun w(message: String, error: Throwable?) { if (BuildConfig.DEBUG) Log.w(TAG, message, error) }
    override fun e(message: String, error: Throwable?) { if (BuildConfig.DEBUG) Log.e(TAG, message, error) }
}
