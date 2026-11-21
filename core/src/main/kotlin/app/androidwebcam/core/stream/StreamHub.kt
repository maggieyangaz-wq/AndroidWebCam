package app.androidwebcam.core.stream

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Decoder configuration for one encoder instance ("generation"). */
class VideoConfig(
    val codec: String,
    val width: Int,
    val height: Int,
    val description: ByteArray,
    val generation: Int,
)

class EncodedFrame(val data: ByteArray, val timestampUs: Long, val isKey: Boolean, val generation: Int)

/**
 * Fan-out of the live stream to viewers. Each viewer has a small bounded queue; when a
 * viewer cannot keep up, its queue is dropped and it resumes at the next keyframe
 * (requested immediately) so latency stays low instead of building up.
 */
class StreamHub(private val listener: Listener, private val queueCapacity: Int = 24) {
    interface Listener {
        fun onKeyframeNeeded() {}
        fun onVideoDemandChanged(active: Boolean) {}
        fun onMjpegDemandChanged(active: Boolean) {}
    }

    @Volatile
    var config: VideoConfig? = null
        private set

    private val video = CopyOnWriteArrayList<VideoSubscriber>()
    private val mjpeg = CopyOnWriteArrayList<MjpegSubscriber>()
    private val lastKeyRequest = AtomicLong(0)

    val videoViewers: Int get() = video.size
    val mjpegViewers: Int get() = mjpeg.size

    fun publishConfig(newConfig: VideoConfig) {
        config = newConfig
        for (s in video) s.offerConfig(newConfig)
    }

    fun publishFrame(frame: EncodedFrame) {
        val current = config ?: return
        if (frame.generation != current.generation) return
        var needKey = false
        for (s in video) if (s.offerFrame(frame)) needKey = true
        if (needKey) requestKeyframe()
    }

    fun publishJpeg(jpeg: ByteArray) {
        for (s in mjpeg) s.offer(jpeg)
    }

    fun requestKeyframe() {
        val now = System.nanoTime() / 1_000_000
        val last = lastKeyRequest.get()
        if (now - last < 250 || !lastKeyRequest.compareAndSet(last, now)) return
        listener.onKeyframeNeeded()
    }

    fun subscribeVideo(): VideoSubscriber {
        val subscriber = VideoSubscriber(queueCapacity)
        config?.let { subscriber.offerConfig(it) }
        video.add(subscriber)
        if (video.size == 1) listener.onVideoDemandChanged(true)
        lastKeyRequest.set(0)
        requestKeyframe()
        return subscriber
    }

    fun subscribeMjpeg(): MjpegSubscriber {
        val subscriber = MjpegSubscriber()
        mjpeg.add(subscriber)
        if (mjpeg.size == 1) listener.onMjpegDemandChanged(true)
        return subscriber
    }

    inner class VideoSubscriber internal constructor(capacity: Int) : Closeable {
        private val queue = ArrayBlockingQueue<Any>(capacity)
        private var waitingForKey = true
        @Volatile var droppedBursts = 0
            private set

        /** Next [VideoConfig] or [EncodedFrame], or null on timeout. */
        fun next(timeoutMs: Long): Any? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        @Synchronized
        internal fun offerConfig(cfg: VideoConfig) {
            queue.clear()
            queue.offer(cfg)
            waitingForKey = true
        }

        /** Returns true when a keyframe should be requested for this subscriber. */
        @Synchronized
        internal fun offerFrame(frame: EncodedFrame): Boolean {
            if (waitingForKey) {
                if (!frame.isKey) return true
                waitingForKey = false
            }
            if (!queue.offer(frame)) {
                // Viewer is too slow: drop the backlog but keep the latest config.
                val cfg = queue.firstOrNull { it is VideoConfig }
                queue.clear()
                if (cfg != null) queue.offer(cfg)
                waitingForKey = true
                droppedBursts++
                return true
            }
            return false
        }

        /** Called by the viewer connection when its decoder needs a fresh keyframe. */
        @Synchronized
        fun resync() {
            waitingForKey = true
            requestKeyframe()
        }

        override fun close() {
            if (video.remove(this) && video.isEmpty()) listener.onVideoDemandChanged(false)
        }
    }

    inner class MjpegSubscriber internal constructor() : Closeable {
        private val slot = ArrayBlockingQueue<ByteArray>(1)

        internal fun offer(jpeg: ByteArray) {
            synchronized(slot) {
                slot.clear() // always deliver the newest picture only
                slot.offer(jpeg)
            }
        }

        fun next(timeoutMs: Long): ByteArray? = slot.poll(timeoutMs, TimeUnit.MILLISECONDS)

        override fun close() {
            if (mjpeg.remove(this) && mjpeg.isEmpty()) listener.onMjpegDemandChanged(false)
        }
    }
}
