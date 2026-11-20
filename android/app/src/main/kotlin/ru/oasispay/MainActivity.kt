package ru.oasispay

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import ru.oasispay.data.ble.BleLink
import android.nfc.NfcAdapter
import ru.oasispay.data.nfc.*
import android.Manifest
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.MotionEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.rubles
import ru.oasispay.ui.PaymentRoute
import ru.oasispay.ui.PaymentViewModel
import ru.oasispay.ui.theme.OasisPayTheme

class MainActivity : ComponentActivity() {

    private data class PermissionDialog(val openSettings: Boolean)
    private var permissionDialog by mutableStateOf<PermissionDialog?>(null)
    private var nfcEnabled by mutableStateOf(false)
    private var useBle by mutableStateOf(false)
    private var bleEnabled by mutableStateOf(false)
    private var grantedBle by mutableStateOf(emptySet<String>())
    private val bluetooth get() = getSystemService(BluetoothManager::class.java).adapter
    private fun blePermissions(role: PaymentRole?) = arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
        if (role == PaymentRole.TERMINAL) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_SCAN)
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun refreshBle() {
        bleEnabled = granted(Manifest.permission.BLUETOOTH_CONNECT) && bluetooth?.isEnabled == true
        grantedBle = PaymentRole.entries.flatMap { blePermissions(it).asList() }.filter(::granted).toSet()
    }
    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refreshBle()
        if (results.any { !it.value && !shouldShowRequestPermissionRationale(it.key) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }
    private fun enableRadio() {
        if (!useBle) startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        else {
            val missing = blePermissions(model.state.value.role).filterNot(::granted)
            if (missing.isNotEmpty()) requestBle.launch(missing.toTypedArray())
            else startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    private val adapter get() = NfcAdapter.getDefaultAdapter(this)
    private val reader get() = (application as OasisPayApplication).reader
    private lateinit var model: PaymentViewModel
    private var authentication: CancellationSignal? = null
    private var needsScreenLock by mutableStateOf(false)
    private var pendingBankAction: (() -> Unit)? = null
    private val localBankPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingBankAction
        pendingBankAction = null
        if (granted) {
            action?.invoke()
        } else if (Build.VERSION.SDK_INT >= 37 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
            permissionDialog = PermissionDialog(true)
        }
    }
    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { nfcEnabled = adapter?.isEnabled == true; refreshBle() }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, nfcReceiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }, ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStop() {
        unregisterReceiver(nfcReceiver)
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        refreshBle()
        reader.activity = this
        nfcEnabled = adapter?.isEnabled == true
        model.retryValidation()
    }
    override fun onPause() {
        reader.activity = null
        TerminalCard.armed = null
        model.cancelExchange()
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured != 0) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        authentication?.cancel()
        authentication = null
        pendingBankAction = null
        super.onDestroy()
    }

    private fun withBankAccess(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        if (pendingBankAction != null) return
        if (model.state.value.role == null) return
        pendingBankAction = action
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
            permissionDialog = PermissionDialog(false)
        } else {
            localBankPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    private fun approvePayment(requestHash: String) {
        val state = model.state.value
        val payment = state.buyer.payment ?: return
        if (state.busy || authentication != null || payment.requestHash != requestHash) return
        if (payment.paymentAttempted || payment.status != null) {
            model.pay(requestHash)
            return
        }
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            needsScreenLock = true
            return
        }
        val signal = CancellationSignal()
        authentication = signal
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Оплата ${rubles(payment.amount)}")
            .setSubtitle("Счёт ${payment.transactionId.takeLast(8).uppercase()}")
            .setDescription("Получатель ${payment.merchantId}")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (authentication !== signal) return
                authentication = null
                if (!isFinishing && !isDestroyed) model.pay(requestHash)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (authentication !== signal) return
                authentication = null
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    model.authenticationFailed()
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        val app = application as OasisPayApplication
        val prefs = getSharedPreferences("radio", MODE_PRIVATE)
        useBle = prefs.getBoolean("useBle", adapter == null)
        reader.useBle = useBle
        val factory = viewModelFactory {
            initializer { PaymentViewModel(app.roles, app.terminal, app.buyer, app.reader, network = app.network.states) }
        }
        model = ViewModelProvider(this, factory)[PaymentViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map { state ->
                    state.busy || (state.role == PaymentRole.TERMINAL &&
                        state.terminal.invoice?.complete == false)
                }.distinctUntilChanged().collect { keepAwake ->
                    if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map { it.role }.distinctUntilChanged().collect { role ->
                    val systemDark = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val dark = role == PaymentRole.TERMINAL || systemDark
                    val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    combine(model.state, snapshotFlow { Triple(useBle, nfcEnabled, bleEnabled) }) { state, radios ->
                        when {
                            state.role != PaymentRole.TERMINAL || !state.armed -> null
                            radios.first && radios.third && blePermissions(state.role).all(::granted) -> "BLE"
                            !radios.first && radios.second -> "NFC"
                            else -> null
                        }
                    }.distinctUntilChanged().collectLatest { radio ->
                        val terminal = ArmedTerminal(app.terminal, model::onTerminalUpdate)
                        TerminalCard.armed = if (radio == "NFC") terminal else null
                        if (radio == "BLE") {
                            try { BleLink.serve(requireNotNull(bluetooth), terminal) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { model.onTerminalUpdate(TerminalUpdate("Не удалось запустить BLE-кассу. Проверьте Bluetooth и разрешения.", stop = true, error = true)) }
                        }
                    }
                } finally { TerminalCard.armed = null }
            }
        }
        setContent {
            PaymentRoute(model, adapter != null, nfcEnabled,
                packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
                useBle, bluetooth != null, bleEnabled, { role -> blePermissions(role).all { it in grantedBle } },
                { selected ->
                    useBle = selected
                    reader.useBle = selected
                    prefs.edit { putBoolean("useBle", selected) }
                }, ::enableRadio,
                { hash -> withBankAccess { approvePayment(hash) } }, ::withBankAccess)
            if (needsScreenLock) {
                OasisPayTheme {
                    AlertDialog(
                        onDismissRequest = { needsScreenLock = false },
                        title = { Text("Защитите оплату блокировкой экрана") },
                        text = { Text("Для подтверждения новой оплаты настройте PIN, пароль или биометрию устройства.") },
                        confirmButton = {
                            TextButton({
                                needsScreenLock = false
                                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }) { Text("Настроить") }
                        },
                        dismissButton = { TextButton({ needsScreenLock = false }) { Text("Позже") } },
                    )
                }
            }
            permissionDialog?.let { dialog ->
                OasisPayTheme {
                    AlertDialog(
                        onDismissRequest = {
                            permissionDialog = null
                            pendingBankAction = null
                        },
                        title = { Text("Доступ к локальной сети") },
                        text = { Text("Разрешите oasis pay подключаться к банку в локальной сети.") },
                        confirmButton = {
                            TextButton({
                                permissionDialog = null
                                if (dialog.openSettings) {
                                    pendingBankAction = null
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)))
                                } else if (Build.VERSION.SDK_INT >= 37) {
                                    localBankPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                                }
                            }) {
                                Text(if (dialog.openSettings) "Открыть настройки" else "Продолжить")
                            }
                        },
                        dismissButton = {
                            TextButton({
                                permissionDialog = null
                                pendingBankAction = null
                            }) { Text("Позже") }
                        },
                    )
                }
            }
        }
    }
}
