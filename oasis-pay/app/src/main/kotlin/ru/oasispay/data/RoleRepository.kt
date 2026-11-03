package ru.oasispay.data

import ru.oasispay.data.local.PaymentPreferences
import ru.oasispay.domain.PaymentRole

internal class RoleRepository(private val preferences: PaymentPreferences) {
    val changes = preferences.changes

    fun current(): PaymentRole? = when (preferences.get("role")) {
        "terminal" -> PaymentRole.TERMINAL
        "buyer" -> PaymentRole.BUYER
        else -> null
    }

    fun choose(role: PaymentRole?) {
        preferences.save("role" to role?.name.orEmpty().lowercase())
    }
}
