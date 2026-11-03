package ru.oasispay.domain

internal fun rubles(kopecks: Long) = "%d,%02d ₽".format(kopecks / 100, kopecks % 100)

internal fun parseAmount(input: String): Long {
    require(Regex("[0-9]{1,4}([,.][0-9]{1,2})?").matches(input.trim())) {
        "Введите сумму в рублях, например 199,50"
    }
    val parts = input.trim().replace(',', '.').split('.')
    val amount = parts[0].toLong() * 100 + (parts.getOrNull(1) ?: "").padEnd(2, '0').toLong()
    require(amount in 1..100_000) { "Допустимая сумма от 0,01 до 1 000 ₽" }
    return amount
}
