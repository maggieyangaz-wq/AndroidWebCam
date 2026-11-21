package app.androidwebcam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import app.androidwebcam.core.Logger
import app.androidwebcam.core.server.NetworkAddresses
import app.androidwebcam.core.server.WebCamServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Tracks which local addresses viewers may connect to: private IPv4 addresses on Wi-Fi,
 * Ethernet, hotspot and USB tethering interfaces. Mobile data and VPN interfaces are
 * excluded, so the camera can never be reached from the internet or a carrier network.
 */
class LanNetworkMonitor(
    context: Context,
    private val logger: Logger,
    private val onChange: (List<Endpoint>) -> Unit,
) : WebCamServer.ConnectionPolicy {
    enum class Kind { WIFI, HOTSPOT, ETHERNET, USB, OTHER }

    data class Endpoint(val kind: Kind, val interfaceName: String, val address: Inet4Address)

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var allowedLocal: Set<InetAddress> = emptySet()
    @Volatile var endpoints: List<Endpoint> = emptyList()
        private set
    private var started = false

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 10_000)
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { refresh() } }
        override fun onLost(network: Network) { handler.post { refresh() } }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { handler.post { refresh() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { handler.post { refresh() } }
    }

    fun start() {
        if (started) return
        started = true
        refresh()
        try {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            logger.w("network callback unavailable", e)
        }
        handler.postDelayed(poll, 10_000)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null) // poll + pending callback-triggered refreshes
        try { cm.unregisterNetworkCallback(callback) } catch (_: RuntimeException) {}
    }

    override fun allow(local: InetAddress, remote: InetAddress): Boolean =
        local in allowedLocal && NetworkAddresses.isLanIpv4(remote) && !remote.isLoopbackAddress

    @Suppress("DEPRECATION") // allNetworks: still the simplest way to see every network on API 29-36
    @Synchronized
    fun refresh() {
        val blocked = HashSet<String>()
        val found = LinkedHashMap<InetAddress, Endpoint>()
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val lp = cm.getLinkProperties(network) ?: continue
                val name = lp.interfaceName ?: continue
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                    else -> Kind.OTHER
                }
                if (kind == null) {
                    blocked.add(name)
                    continue
                }
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && NetworkAddresses.isLanIpv4(addr)) found[addr] = Endpoint(kind, name, addr)
                }
            }
        } catch (e: RuntimeException) {
            logger.w("connectivity query failed", e)
        }
        // Hotspot / USB tethering interfaces are not "networks" for ConnectivityManager.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                val name = ni.name ?: continue
                if (name in blocked || BLOCKED_NAMES.containsMatchIn(name)) continue
                // Some ROMs refuse interface flag queries; an interface with a LAN IPv4 address is then assumed up.
                val up = try { ni.isUp && !ni.isLoopback && !ni.isPointToPoint } catch (e: Exception) { true }
                if (!up) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && NetworkAddresses.isLanIpv4(addr) && addr !in found) {
                        found[addr] = Endpoint(kindOf(name), name, addr)
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("interface enumeration failed", e)
        }
        val result = found.values.sortedBy { it.kind.ordinal }
        allowedLocal = result.map { it.address }.toSet()
        if (result != endpoints) {
            endpoints = result
            onChange(result)
        }
    }

    private fun kindOf(name: String): Kind = when {
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> Kind.USB
        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> Kind.HOTSPOT
        name.startsWith("eth") -> Kind.ETHERNET
        else -> Kind.OTHER
    }

    private companion object {
        /** Cellular, VPN/tunnel, loopback and other non-LAN interfaces. */
        val BLOCKED_NAMES = Regex("^(rmnet|ccmni|pdp|ppp|wwan|seth|v4-|clat|tun|ipsec|dummy|lo$|p2p|aware|nan|ifb|gre|sit|ip6|ip_vti|uwbr|epdg|bt-)")
    }
}
