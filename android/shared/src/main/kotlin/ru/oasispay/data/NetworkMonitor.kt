package ru.oasispay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class NetworkState(val label: String = "Проверяем сеть", val available: Boolean = false,
                                 val internetValidated: Boolean = false)

/** Android connectivity is separate from successful bank HTTPS; no background requests from the offline terminal. */
internal class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    val states = callbackFlow {
        fun publish() {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val transport = when {
                caps == null -> "Офлайн · сети нет"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Локальное подключение"
            }
            trySend(NetworkState(if (validated) "$transport · интернет есть" else transport,
                available = caps != null, internetValidated = validated))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        manager.registerDefaultNetworkCallback(callback)
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
