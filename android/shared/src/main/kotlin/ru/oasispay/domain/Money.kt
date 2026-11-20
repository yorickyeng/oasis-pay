package ru.oasispay.domain

import java.util.Locale

private val AMOUNT = Regex("[0-9]{1,4}([,.][0-9]{1,2})?")

internal fun rubles(kopecks: Long): String {
    val whole = "%,d".format(Locale.ROOT, kopecks / 100).replace(',', '\u00a0')
    return "%s,%02d ₽".format(Locale.ROOT, whole, kopecks % 100)
}

internal class AmountInputException(message: String) : IllegalArgumentException(message)

internal fun parseAmount(input: String): Long {
    if (!AMOUNT.matches(input.trim())) {
        throw AmountInputException("Введите сумму в рублях, например 199,50")
    }
    val parts = input.trim().replace(',', '.').split('.')
    val amount = parts[0].toLong() * 100 + (parts.getOrNull(1) ?: "").padEnd(2, '0').toLong()
    if (amount !in 1..100_000) throw AmountInputException("Допустимая сумма от 0,01 до 1 000 ₽")
    return amount
}
