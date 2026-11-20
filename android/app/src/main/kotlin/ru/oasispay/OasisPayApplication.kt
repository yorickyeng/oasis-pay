package ru.oasispay

import android.app.Application
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.RoleRepository
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.local.PaymentStore

internal class OasisPayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val routes = ru.oasispay.data.bank.BankWifiRoute(getSystemService(android.net.ConnectivityManager::class.java))
        ru.oasispay.data.bank.BankClient.wifiSocketFactory = routes::socketFactory
    }

    private val store by lazy { PaymentStore(this) }
    val network by lazy { ru.oasispay.data.NetworkMonitor(this) }
    val reader by lazy { ru.oasispay.data.nfc.BuyerReader(buyer) }
    val roles by lazy { RoleRepository(store) }
    val terminal by lazy { TerminalRepository(store) }
    val buyer by lazy { BuyerRepository(store) }
}
