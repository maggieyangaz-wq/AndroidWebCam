package app.androidwebcam.core

import app.androidwebcam.core.cloud.CloudCredential
import app.androidwebcam.core.cloud.CloudInstructions
import app.androidwebcam.core.cloud.Ecdsa
import app.androidwebcam.core.cloud.RelayClient
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's relay client (core) against a local relay, for tools/run-relay-tests.sh.
 * Usage: <relay url> <key dir with key.der (PKCS#8) and spki.der> <output dir>
 * Writes command.txt (the agent command, generated like in the app), picture.jpg (what the
 * camera returns) and ready (once connected); then serves until killed.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: <relay url> <key dir> <output dir>" }
    val (relay, keyDir, outDir) = args
    val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(File(keyDir, "key.der").readBytes()))
    val spki = File(keyDir, "spki.der").readBytes()
    val agent = CloudCredential.create()
    val picture = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(30_000) { (it * 7).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    File(outDir, "picture.jpg").writeBytes(picture)
    File(outDir, "command.txt").writeText(CloudInstructions.command(relay, agent))
    val lastUse = ConcurrentHashMap<String, Long>()

    val client = RelayClient(
        relay,
        object : RelayClient.PhoneIdentity {
            override val spki = spki
            override fun sign(message: ByteArray): ByteArray =
                Ecdsa.derToRaw(Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(message) }.sign())
        },
        object : RelayClient.AgentDirectory {
            override fun list() = listOf(RelayClient.Agent(agent.agentId, agent.authHash))
            override fun key(id: String) = if (id == agent.agentId) agent.e2eKey else null
            override fun admit(id: String): Int? {
                if (id != agent.agentId) return 401
                val now = System.currentTimeMillis()
                if (now - (lastUse[id] ?: 0) < 5_000) return 429 // the phone's own limit (relay: 2 s)
                lastUse[id] = now
                return null
            }
        },
        camera = { picture },
        listener = object : RelayClient.Listener {
            override fun onStatus(status: RelayClient.Status, detail: String?) {
                println("relay status: $status ${detail ?: ""}")
                if (status == RelayClient.Status.CONNECTED) File(outDir, "ready").writeText("ok")
            }
        },
        connector = when {
            !relay.startsWith("https://") -> RelayClient.Connector.LOOPBACK_FOR_TESTS
            System.getenv("HTTPS_PROXY") != null -> RelayClient.Connector.tls(proxyTunnel(java.net.URI(System.getenv("HTTPS_PROXY"))))
            else -> RelayClient.Connector.tls()
        },
    )
    client.start()
    Thread.currentThread().join()
}

/** Test machines may only reach the internet through an HTTP proxy (CONNECT); phones connect directly. */
private fun proxyTunnel(proxy: java.net.URI): (String, Int, Int) -> java.net.Socket = { host, port, timeoutMs ->
    val socket = java.net.Socket()
    socket.connect(java.net.InetSocketAddress(proxy.host, proxy.port), timeoutMs)
    socket.soTimeout = timeoutMs
    socket.getOutputStream().apply {
        write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
        flush()
    }
    val head = StringBuilder()
    val input = socket.getInputStream()
    while (!head.endsWith("\r\n\r\n")) {
        val b = input.read()
        require(b >= 0 && head.length < 8192) { "proxy closed the connection" }
        head.append(b.toChar())
    }
    require(head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200")) { "proxy refused: ${head.lineSequence().first()}" }
    socket
}
