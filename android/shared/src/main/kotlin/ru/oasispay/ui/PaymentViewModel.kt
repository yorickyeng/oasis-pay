package ru.oasispay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.oasispay.data.NetworkState
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.oasispay.data.bank.BankRejectedException
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.RoleRepository
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.nfc.BuyerReader
import ru.oasispay.data.nfc.TerminalUpdate
import ru.oasispay.data.nfc.RelayException
import ru.oasispay.domain.AmountInputException
import ru.oasispay.data.protocol.DeviceKeyException
import ru.oasispay.data.protocol.now
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.TerminalState
import ru.oasispay.domain.parseAmount
import java.io.IOException
import javax.net.ssl.SSLException

internal class PaymentViewModel(
    private val roles: RoleRepository,
    private val terminal: TerminalRepository,
    private val buyer: BuyerRepository,
    private val reader: BuyerReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    network: Flow<NetworkState> = flowOf(NetworkState()),
) : ViewModel() {

    private data class Controls(
        val armed: Boolean = false,
        val busy: Boolean = false,
        val message: String = "",
        val error: Boolean = false,
        val preview: PaymentPreview? = null,
        val events: List<ExchangeEvent> = emptyList(),
        val terminalPeerConnected: Boolean = false,
        val scanning: Boolean = false,
    )

    private val controls = MutableStateFlow(Controls())
    private var exchange: Job? = null
    private val clock = flow {
        while (true) {
            emit(now())
            delay(1000)
        }
    }

    private val reloads = MutableStateFlow(0L)
    private val snapshots = combine(roles.changes, reloads) { _, _ ->
        try {
            snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PaymentUiState(null, TerminalState(false, null), BuyerState(false, "", null),
                initialized = true, storageHealthy = false, recoveryMessage = errorMessage(error))
        }
    }.flowOn(ioDispatcher)

    fun retryValidation() {
        reloads.update { it + 1 }
    }

    val state = combine(snapshots, controls, clock, network) { data, ui, now, connectivity ->
        data.copy(armed = ui.armed, busy = ui.busy, message = ui.message, error = ui.error,
            preview = ui.preview?.takeIf { it.requestHash == data.buyer.payment?.requestHash }, now = now,
            network = connectivity, events = ui.events, terminalPeerConnected = ui.terminalPeerConnected, scanning = ui.scanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
        PaymentUiState(null, TerminalState(false, null), BuyerState(false, "", null)))

    private suspend fun snapshot() = PaymentUiState(
        roles.current(), terminal.snapshot(), buyer.snapshot(), initialized = true,
    )

    fun choose(role: PaymentRole?) {
        if (controls.value.armed) return
        task {
            roles.choose(role)
            controls.update { it.copy(preview = null) }
            ""
        }
    }

    fun enroll(url: String, pin: String, code: String) = task {
        terminal.enroll(url, pin, code)
        "Касса зарегистрирована. Теперь можно отключить Wi‑Fi и мобильную сеть."
    }

    fun checkBank(url: String, pin: String) = task {
        ru.oasispay.data.bank.BankClient.checkConnection(url, pin)
        "Связь с банком есть. Адрес, сертификат и база данных проверены."
    }

    fun configureBuyer(url: String, pin: String, code: String) = task {
        buyer.configure(url, pin, code)
        log("Регистрация покупателя проверена · банковский сертификат COSE / ES256")
        buyer.refreshAccount()
        "Баланс получен из банка · подпись проверена"
    }

    fun createOrder(amount: String, available: Boolean) = task {
        requireRadio(available)
        terminal.createOrder(parseAmount(amount), if (reader.useBle) "ble-l2cap/1" else "nfc-isodep/1")
        controls.update { it.copy(armed = true) }
        "Включаем обмен на кассе…"
    }

    fun arm(available: Boolean) =
        local {
        requireRadio(available)
        controls.update { it.copy(armed = true, message = "Включаем обмен на кассе…", error = false) }
    }

    fun pause() {
        controls.update { it.copy(armed = false, message = "", error = false, terminalPeerConnected = false) }
    }

    fun onTerminalUpdate(update: TerminalUpdate) {
        log(update.message, update.error)
        controls.update { it.copy(armed = it.armed && !update.stop, message = update.message, error = update.error,
            terminalPeerConnected = update.peerConnected ?: it.terminalPeerConnected) }
    }

    fun authenticationFailed() {
        show("Не удалось подтвердить владельца устройства. Повторите подтверждение оплаты.", true)
    }

    fun scan(available: Boolean) = local {
        requireRadio(available)
        exchange = task {
            try {
                show("Ищем кассу рядом · приложите телефон к кассе")
                reader.exchange { log(it) }
                finishRelay()
                ""
            } finally {
                controls.update { it.copy(scanning = false) }
            }
        }
        if (exchange != null) controls.update { it.copy(scanning = true) }
    }

    fun cancelExchange() {
        exchange?.cancel()
    }

    fun fulfill(available: Boolean) = task {
        terminal.fulfill()
        controls.update { it.copy(armed = available) }
        if (available) "Включаем обмен на кассе…"
        else "Выдача сохранена. Включите выбранный способ связи для передачи подтверждения."
    }

    fun preview(requestHash: String) = task {
        val checked = buyer.preview(requestHash)
        controls.update { it.copy(preview = checked) }
        ""
    }

    fun pay(requestHash: String) = task {
        log("Запрос оплаты или получение прежнего результата · HTTPS / TLS 1.3")
        buyer.pay(requestHash)
        log("Ответ банка получен · COSE Sign1 / ES256 · финансовый результат сохранён")
        deliverResult()
        finishRelay()
        ""
    }

    fun resolve(requestHash: String) = task {
        buyer.resolve(requestHash)
        deliverResult()
        finishRelay()
        ""
    }

    fun refreshAccount() = task {
        buyer.refreshAccount()
        "Баланс обновлён · подпись банка проверена"
    }

    private suspend fun deliverResult() {
        show("Приложите телефон к кассе ещё раз для передачи результата")
        reader.exchange { log(it) }
    }

    private suspend fun finishRelay() {
        val payment = buyer.snapshot().payment
        if (payment?.hasAcknowledgement == true && !payment.acknowledgementSent) {
            buyer.confirmDelivery(payment.requestHash)
            log("Подтверждение кассы автоматически доставлено в банк")
        }
        if (payment?.status != null) {
            try {
                buyer.refreshAccount()
                log("Баланс обновлён · подпись банка проверена")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                log("Не удалось обновить баланс · показан последний подтверждённый снимок", true)
            }
        }
    }

    fun confirmDelivery(requestHash: String) = task {
        buyer.confirmDelivery(requestHash)
        ""
    }

    fun resetBuyer(requestHash: String) = task {
        buyer.resetUnattempted(requestHash)
        controls.update { it.copy(preview = null) }
        ""
    }

    private fun requireRadio(available: Boolean) {
        check(available) { "Включите выбранный способ связи." }
    }

    private fun task(work: suspend () -> String): Job? {
        if (controls.value.busy || !state.value.storageHealthy) return null
        controls.update { it.copy(busy = true, message = "", error = false) }
        return viewModelScope.launch {
            try {
                show(withContext(ioDispatcher) { work() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                show(errorMessage(error), true)
            } finally {
                controls.update { it.copy(busy = false) }
            }
        }
    }

    private fun local(action: () -> Unit) {
        if (controls.value.busy || !state.value.storageHealthy) return
        try {
            action()
        } catch (error: Exception) {
            show(errorMessage(error), true)
        }
    }

    private fun errorMessage(error: Exception) = when (error) {
        is ru.oasispay.data.protocol.BankClockException -> requireNotNull(error.message)
        is BankRejectedException -> when (error.code) {
            "database_unavailable" -> "Банк доступен, но его база данных не отвечает"
            "unauthorized" -> "Банк не подтвердил доступ. Проверьте код выбранной роли или регистрацию покупателя"
            "request_expired" -> "Срок счёта истёк. Уточните итоговый статус в банке"
            "request_still_active" -> "Счёт ещё действует. Повторите сверку после истечения срока"
            "sequence_replayed", "conflict" -> "Идентификатор операции требует сверки в банке"
            "terminal_certificate_expired", "enrollment_unavailable" -> "Продлите или восстановите регистрацию устройства в банке"
            else -> "Банк отклонил запрос. Повторите с тем же счётом или проверьте реестр банка"
        }
        is android.database.sqlite.SQLiteFullException -> "Освободите место на устройстве и повторите с тем же счётом"
        is android.database.sqlite.SQLiteDatabaseCorruptException -> "Журнал требует восстановления. Сохраните данные и обратитесь к оператору банка"
        is android.database.sqlite.SQLiteException -> "Не удалось открыть или записать журнал. Проверьте хранилище устройства и повторите проверку"
        is DeviceKeyException -> requireNotNull(error.message)
        is java.security.GeneralSecurityException -> "Проверка ключа или подписи не пройдена. Требуется проверка регистрации"

        is SSLException -> "Защищённое подключение к банку не установлено. Проверьте адрес, отпечаток и дату."
        is java.net.ConnectException, is java.net.SocketTimeoutException -> "Банк не отвечает по указанному адресу. Проверьте Wi-Fi, IP и порт Mac, запуск Docker и доступ между устройствами."
        is IOException -> "Нет связи с банком. Проверьте сеть и повторите."
        is AmountInputException -> requireNotNull(error.message)
        is RelayException -> requireNotNull(error.message)
        else -> "Не удалось проверить или сохранить операцию. Проверьте параметры и повторите с тем же счётом."
    }

    private fun log(message: String, error: Boolean = false) {
        if (message.isEmpty()) return
        controls.update { old ->
            if (old.events.lastOrNull()?.message == message) old
            else old.copy(events = (old.events + ExchangeEvent(now(), message.take(400), error)).takeLast(32))
        }
    }

    private fun show(message: String, error: Boolean = false) {
        log(message, error)
        controls.update { it.copy(message = message, error = error) }
    }
}
