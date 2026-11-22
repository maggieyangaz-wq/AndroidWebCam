package app.androidwebcam.core

import app.androidwebcam.core.auth.AccessControl
import app.androidwebcam.core.server.WebCamServer
import app.androidwebcam.core.stream.Avc
import app.androidwebcam.core.stream.EncodedFrame
import app.androidwebcam.core.stream.StreamHub
import app.androidwebcam.core.stream.VideoConfig
import app.androidwebcam.core.tls.IdentityKeyManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Development server used for browser end-to-end tests of the viewer. Only loopback
 * clients are accepted. Writes {"code": ..., "pair": ..., "fingerprint": ..., "pin": ...} to the info file.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: <port> <file.h264> <jpegDir> <infoJsonOut>" }
    val port = args[0].toInt()
    val accessUnits = splitAccessUnits(File(args[1]).readBytes())
    val jpegs = File(args[2]).listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }.map { it.readBytes() }
    val identity = TestSupport.softwareIdentity()
    val access = AccessControl()
    val hub = StreamHub(object : StreamHub.Listener {})
    val server = WebCamServer(
        WebCamServer.Options(port = port, appVersion = "dev"),
        IdentityKeyManager(identity),
        access,
        hub,
        { _, remote -> remote.isLoopbackAddress },
    )
    server.start()
    File(args[3]).writeText(
        "{\"code\":\"${access.displayCode}\",\"pair\":\"${access.issuePairingToken()}\",\"fingerprint\":\"${identity.fingerprintSha256}\"," +
            "\"pin\":\"${identity.publicKeyPin}\"}",
    )
    println("dev server on https://127.0.0.1:$port  code=${access.displayCode}")

    Thread({
        var index = 0L
        var configured = false
        while (true) {
            for (au in accessUnits) {
                val unit = Avc.convertAccessUnit(au)
                if (!configured && unit.sps != null && unit.pps != null) {
                    hub.publishConfig(VideoConfig(Avc.codecString(unit.sps!!), 1280, 720, Avc.decoderConfigurationRecord(unit.sps!!, unit.pps!!), 1))
                    configured = true
                }
                hub.publishFrame(EncodedFrame(unit.avcc, index * 33_333, unit.isKey, 1))
                index++
                Thread.sleep(33)
            }
        }
    }, "h264-feeder").apply { isDaemon = true }.start()

    Thread({
        while (true) {
            for (jpeg in jpegs) {
                hub.publishJpeg(jpeg)
                Thread.sleep(100)
            }
        }
    }, "mjpeg-feeder").apply { isDaemon = true }.start()

    Thread.currentThread().join()
}

/** Splits an Annex B elementary stream (encoded with AUD NAL units) into access units. */
private fun splitAccessUnits(stream: ByteArray): List<ByteArray> {
    val units = ArrayList<ByteArray>()
    var current = ByteArrayOutputStream()
    for (nal in Avc.splitAnnexB(stream)) {
        if (Avc.nalType(nal) == Avc.NAL_AUD && current.size() > 0) {
            units.add(current.toByteArray())
            current = ByteArrayOutputStream()
        }
        current.write(byteArrayOf(0, 0, 0, 1))
        current.write(nal)
    }
    if (current.size() > 0) units.add(current.toByteArray())
    return units
}
