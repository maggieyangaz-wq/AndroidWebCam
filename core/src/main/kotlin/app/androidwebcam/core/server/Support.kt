package app.androidwebcam.core.server

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object NetworkAddresses {
    /** RFC 1918 private ranges and IPv4 link-local. Carrier-grade NAT (100.64/10) is excluded. */
    fun isLanIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return a0 == 10 || (a0 == 172 && a1 in 16..31) || (a0 == 192 && a1 == 168) || (a0 == 169 && a1 == 254)
    }
}

object UserAgents {
    /** Coarse, privacy-friendly description such as "Chrome · Windows". */
    fun describe(userAgent: String?): String {
        val ua = userAgent ?: return "Unknown"
        if (ua.startsWith("AndroidWebCam-AI/")) {
            // The AI bridge on a computer (awc-ai.mjs): "AndroidWebCam-AI/1.0.0 (Windows)".
            val platform = ua.substringAfter('(', "").substringBefore(')')
            val os = when {
                "Windows" in platform -> "Windows"
                "Macintosh" in platform -> "macOS"
                "Linux" in platform -> "Linux"
                else -> ""
            }
            return if (os.isEmpty()) "AI tool" else "AI tool · $os"
        }
        val browser = when {
            "Edg/" in ua || "EdgA/" in ua || "EdgiOS/" in ua -> "Edge"
            "OPR/" in ua -> "Opera"
            "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
            "SamsungBrowser/" in ua -> "Samsung Internet"
            "CriOS/" in ua || "Chrome/" in ua -> "Chrome"
            "Safari/" in ua -> "Safari"
            else -> "Browser"
        }
        val os = when {
            "Windows" in ua -> "Windows"
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Android" in ua -> "Android"
            "CrOS" in ua -> "ChromeOS"
            "Macintosh" in ua || "Mac OS X" in ua -> "macOS"
            "Linux" in ua -> "Linux"
            else -> ""
        }
        return if (os.isEmpty()) browser else "$browser · $os"
    }
}

internal fun closeQuietly(c: Closeable?) {
    try {
        c?.close()
    } catch (_: Exception) {
    }
}

internal fun daemonThreads(prefix: String): ThreadFactory {
    val n = AtomicInteger(1)
    return ThreadFactory { r -> Thread(r, "$prefix-${n.getAndIncrement()}").apply { isDaemon = true } }
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

/**
 * Closes sockets whose single write has been blocked for too long (a viewer that
 * vanished without closing TCP), so streaming threads never hang indefinitely.
 */
internal class WriteWatchdog(private val limitMs: Long = 15_000) {
    private val active = ConcurrentHashMap<Socket, Long>()
    private var timer: ScheduledExecutorService? = null

    fun start() {
        timer = Executors.newSingleThreadScheduledExecutor(daemonThreads("awc-watchdog")).also {
            it.scheduleWithFixedDelay({ sweep() }, 2, 2, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        timer?.shutdownNow()
        timer = null
        active.clear()
    }

    fun <T> guard(socket: Socket, block: () -> T): T {
        active[socket] = monotonicMillis()
        try {
            return block()
        } finally {
            active.remove(socket)
        }
    }

    private fun sweep() {
        val now = monotonicMillis()
        for ((socket, since) in active) {
            if (now - since > limitMs) {
                active.remove(socket)
                closeQuietly(socket)
            }
        }
    }
}

internal class StaticAssets private constructor(private val files: Map<String, Asset>) {
    class Asset(val bytes: ByteArray, val contentType: String)

    operator fun get(name: String): Asset? = files[name]

    companion object {
        private val TYPES = linkedMapOf(
            "index.html" to "text/html; charset=utf-8",
            "app.js" to "text/javascript; charset=utf-8",
            "style.css" to "text/css; charset=utf-8",
            "favicon.svg" to "image/svg+xml",
            WebCamServer.AI_BRIDGE to "text/javascript; charset=utf-8",
        )

        fun load(): StaticAssets = StaticAssets(TYPES.mapValues { (name, type) -> Asset(read("/web/$name"), type) })

        private fun read(path: String): ByteArray {
            val stream = StaticAssets::class.java.getResourceAsStream(path) ?: error("missing web asset $path")
            return stream.use { it.readBytes() }
        }
    }
}
