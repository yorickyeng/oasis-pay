package ru.oasispay.data.bank

import android.net.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/** Listen for directly attached Wi-Fi subnets, including Wi-Fi without Internet validation. */
internal class BankWifiRoute(private val manager: ConnectivityManager) {
    private val links = ConcurrentHashMap<Network, LinkProperties>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            links[network] = linkProperties
        }
        override fun onLost(network: Network) { links.remove(network) }
    }
    init {
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }

    fun socketFactory(host: String): SocketFactory? {
        if (!InetAddresses.isNumericAddress(host)) return null
        val address: InetAddress = InetAddresses.parseNumericAddress(host)
        if (!address.isSiteLocalAddress || address.isLoopbackAddress) return null
        val active = manager.getNetworkCapabilities(manager.activeNetwork)
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true ||
            active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return links.entries.firstOrNull { (_, properties) ->
            properties.routes.any { !it.isDefaultRoute && !it.hasGateway() && it.destination.contains(address) }
        }?.key?.socketFactory
    }
}
