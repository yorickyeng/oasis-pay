package ru.oasispay.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PaymentPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("oasis-pay", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        revision.update { it + 1 }
    }

    val changes = revision.asStateFlow()

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun get(key: String): String = preferences.getString(key, "").orEmpty()

    fun save(vararg values: Pair<String, String>) {
        val editor = preferences.edit()
        for ((key, value) in values) editor.putString(key, value)
        check(editor.commit()) { "Не удалось сохранить операцию" }
    }
}
