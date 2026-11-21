package app.androidwebcam.core

/**
 * Minimal logging facade so the core module stays free of Android dependencies.
 *
 * Implementations must never be handed secrets (access codes, tokens, keys);
 * callers in this project only log technical state.
 */
interface Logger {
    fun d(message: String) {}
    fun i(message: String) {}
    fun w(message: String, error: Throwable? = null) {}
    fun e(message: String, error: Throwable? = null) {}

    companion object {
        val NONE: Logger = object : Logger {}
    }
}
