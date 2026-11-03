package ru.oasispay

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.oasispay.data.nfc.TerminalNfcReader
import ru.oasispay.domain.PaymentRole
import ru.oasispay.ui.PaymentRoute
import ru.oasispay.ui.PaymentViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as OasisPayApplication
        val factory = viewModelFactory {
            initializer { PaymentViewModel(app.roles, app.terminal, app.buyer, app.nfcSession) }
        }
        val model = ViewModelProvider(this, factory)[PaymentViewModel::class.java]
        val reader = TerminalNfcReader(this, app.terminal, app.nfcSession, model::onNfcExchange)
        val supportsHce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    model.state.map { it.role to it.armed }.distinctUntilChanged().collect { (role, armed) ->
                        reader.configure(role == PaymentRole.BUYER && supportsHce, role == PaymentRole.TERMINAL && armed)
                    }
                } finally {
                    reader.configure(buyerActive = false, terminalArmed = false)
                }
            }
        }
        setContent { PaymentRoute(model, supportsHce, reader::available) }
    }
}
