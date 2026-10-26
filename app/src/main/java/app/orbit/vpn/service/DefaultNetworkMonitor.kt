package app.orbit.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the underlying (non-VPN) default network and reports it to sing-box,
 * which binds outgoing sockets to it.
 */
class DefaultNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler by lazy { Handler(HandlerThread("network-monitor").apply { start() }.looper) }

    @Volatile
    var defaultNetwork: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChanged(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == defaultNetwork) onChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == defaultNetwork) onChanged(network)
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) onChanged(null)
        }
    }

    fun start() {
        if (registered) return
        // Request (not listen) so that we get the physical default network rather than our own VPN.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            connectivity.requestNetwork(request, callback, handler)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connectivity.registerDefaultNetworkCallback(callback, handler)
        } else {
            connectivity.registerDefaultNetworkCallback(callback)
        }
        registered = true
        defaultNetwork = connectivity.activeNetwork
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        defaultNetwork = null
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        notifyListener(defaultNetwork)
    }

    private fun onChanged(network: Network?) {
        defaultNetwork = network
        notifyListener(network)
    }

    private fun notifyListener(network: Network?) {
        val listener = listener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        // Link properties may lag slightly behind onAvailable.
        repeat(10) {
            val name = connectivity.getLinkProperties(network)?.interfaceName
            val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
            if (name != null && index != null) {
                val caps = connectivity.getNetworkCapabilities(network)
                val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                listener.updateDefaultInterface(name, index, metered, false)
                return
            }
            Thread.sleep(100)
        }
    }
}
