package app.vproxies.sa.bg

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
 * Tracks the underlying (non-VPN) default network and reports it to sing-box so outbound
 * proxy connections are bound to the real Wi-Fi / mobile interface.
 */
object DefaultNetworkMonitor {
    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var connectivity: ConnectivityManager? = null
    private var listener: InterfaceUpdateListener? = null
    private var registered = false
    private val thread by lazy { HandlerThread("VProxiesNetwork").apply { start() } }

    private val request: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == defaultNetwork) notifyListener(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == defaultNetwork) notifyListener(network)
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) update(null)
        }
    }

    @Synchronized
    fun start(connectivity: ConnectivityManager) {
        this.connectivity = connectivity
        if (registered) return
        defaultNetwork = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        }
        val handler = Handler(thread.looper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
        } else {
            connectivity.requestNetwork(request, callback, handler)
        }
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
        defaultNetwork = null
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        notifyListener(defaultNetwork)
    }

    private fun update(network: Network?) {
        defaultNetwork = network
        notifyListener(network)
    }

    private fun notifyListener(network: Network?) {
        val listener = listener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        repeat(10) {
            val linkProperties = connectivity?.getLinkProperties(network)
            val name = linkProperties?.interfaceName
            val index = runCatching { NetworkInterface.getByName(name).index }.getOrNull()
            if (name != null && index != null) {
                val caps = connectivity?.getNetworkCapabilities(network)
                val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                listener.updateDefaultInterface(name, index, expensive, false)
                return
            }
            Thread.sleep(100)
        }
    }
}
