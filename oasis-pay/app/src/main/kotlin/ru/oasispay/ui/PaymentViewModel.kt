package ru.oasispay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.RoleRepository
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.nfc.NfcSession
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.parseAmount
import java.io.IOException

internal class PaymentViewModel(
    private val roles: RoleRepository,
    private val terminal: TerminalRepository,
    private val buyer: BuyerRepository,
    private val nfcSession: NfcSession,
) : ViewModel() {
    private data class Controls(
        val armed: Boolean = false,
        val busy: Boolean = false,
        val message: String = "",
        val preview: PaymentPreview? = null,
    )

    private val controls = MutableStateFlow(Controls())
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(1000)
        }
    }

    val state = combine(roles.changes.map { snapshot() }, controls, clock) { data, ui, now ->
        data.copy(
            armed = ui.armed && data.terminal.invoice?.acknowledgementHanded == false,
            busy = ui.busy,
            message = ui.message,
            preview = ui.preview?.takeIf { it.requestHash == data.buyer.payment?.requestHash },
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), snapshot())

    private fun snapshot() = PaymentUiState(roles.current(), terminal.snapshot(), buyer.snapshot())

    fun choose(role: PaymentRole?) = local {
        if (controls.value.busy || state.value.armed) return@local
        roles.choose(role)
        controls.value = Controls()
    }

    fun enroll(url: String, pin: String, code: String) = bankTask {
        terminal.enroll(url, pin, code)
        "Касса зарегистрирована. Теперь можно отключить Wi-Fi и мобильную сеть."
    }

    fun createOrder(amount: String, nfcAvailable: Boolean) = local {
        requireNfc(nfcAvailable)
        terminal.createOrder(parseAmount(amount))
        startNfc()
    }

    fun arm(nfcAvailable: Boolean) = local {
        requireNfc(nfcAvailable)
        startNfc()
    }

    private fun startNfc() {
        controls.update { it.copy(armed = true, message = "Приложите задние стороны разблокированных телефонов.") }
    }

    fun pauseNfc() {
        controls.update { it.copy(armed = false, message = "Обмен приостановлен. Операция сохранена.") }
    }

    fun closeOrder() = local { terminal.closeExpiredOrder() }

    fun onNfcExchange(outcome: Result<Boolean>) {
        if (!controls.value.armed) return
        outcome.fold(
            onSuccess = { delivered ->
                controls.update {
                    if (delivered) it.copy(armed = false, message = "Результат банка проверен и сохранён.")
                    else it.copy(message = "Запрос передан. После подтверждения поднесите телефоны ещё раз.")
                }
            },
            onFailure = { error ->
                showMessage(if (error is IOException) "Связь NFC оборвалась. Повторите касание."
                else error.message ?: "Ошибка обмена NFC")
            },
        )
    }

    fun configureBuyer(url: String, pin: String, code: String): Boolean = local {
        buyer.configure(url, pin, code)
        showMessage("Параметры сохранены. Поднесите телефон к кассе.")
    }

    fun preview(requestHash: String) = bankTask {
        val checked = buyer.preview(requestHash)
        controls.update { it.copy(preview = checked) }
        ""
    }

    fun pay(requestHash: String) = bankTask {
        check(controls.value.preview?.requestHash == requestHash)
        buyer.pay(requestHash)
        "Поднесите телефон к кассе повторно."
    }

    fun confirmDelivery(requestHash: String) = bankTask {
        buyer.confirmDelivery(requestHash)
        "Доставка подтверждена банком."
    }

    fun resetBuyer(requestHash: String) = local {
        buyer.reset(requestHash)
        controls.update { it.copy(preview = null, message = "") }
    }

    private fun bankTask(work: () -> String) {
        if (controls.value.busy) return
        controls.update { it.copy(busy = true, message = "") }
        nfcSession.buyerBusy = true
        viewModelScope.launch {
            try {
                showMessage(withContext(Dispatchers.IO) { work() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(error.message ?: "Операция не выполнена")
            } finally {
                nfcSession.buyerBusy = false
                controls.update { it.copy(busy = false) }
            }
        }
    }

    private fun local(action: () -> Unit): Boolean = runCatching(action).fold(
        onSuccess = { true },
        onFailure = {
            showMessage(it.message ?: "Действие не выполнено")
            false
        },
    )

    private fun showMessage(message: String) {
        controls.update { it.copy(message = message) }
    }

    private fun requireNfc(available: Boolean) {
        check(available) { "Включите NFC в настройках телефона" }
    }
}
