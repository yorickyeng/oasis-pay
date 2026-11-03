package ru.oasispay

import android.app.Application
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.RoleRepository
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.local.PaymentPreferences
import ru.oasispay.data.nfc.NfcSession

internal class OasisPayApplication : Application() {
    private val preferences by lazy { PaymentPreferences(this) }
    val roles by lazy { RoleRepository(preferences) }
    val terminal by lazy { TerminalRepository(preferences) }
    val buyer by lazy { BuyerRepository(preferences) }
    val nfcSession = NfcSession()
}
