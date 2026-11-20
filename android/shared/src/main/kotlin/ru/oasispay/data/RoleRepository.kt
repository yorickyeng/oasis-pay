package ru.oasispay.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.oasispay.data.local.PaymentStore
import ru.oasispay.domain.PaymentRole

internal class RoleRepository(
    private val store: PaymentStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val changes = store.changes

    suspend fun current(): PaymentRole? = withContext(io) {
        store.read {
            when (store.get("role")) {
                "terminal" -> PaymentRole.TERMINAL
                "buyer" -> PaymentRole.BUYER
                "" -> null
                else -> error("Неизвестная роль в локальном хранилище")
            }
        }
    }

    suspend fun choose(role: PaymentRole?) = withContext(io) {
        store.save("role" to role?.name.orEmpty().lowercase())
    }
}
