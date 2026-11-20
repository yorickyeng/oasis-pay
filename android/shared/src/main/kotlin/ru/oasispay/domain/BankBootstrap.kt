package ru.oasispay.domain

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Public endpoint/pin plus one role's enrollment secret. Never contains device keys or access tokens. */
internal data class BankBootstrap(val url: String, val pin: String, val code: String) {
    fun validated(): BankBootstrap {
        val endpoint = url.trim().trimEnd('/').toHttpUrl()
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.encodedPath == "/" && endpoint.query == null && endpoint.fragment == null && url.length <= 256) {
            "Нужен HTTPS-адрес банка без пути и логина"
        }
        val fingerprint = pin.filterNot { it.isWhitespace() || it == ':' }.lowercase()
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Нужен TLS SHA-256 из консоли банка" }
        require(code.trim().matches(Regex("[A-Za-z0-9_-]{32}"))) { "Нужен код подключения выбранной роли" }
        return BankBootstrap(endpoint.toString().trimEnd('/'), fingerprint, code.trim())
    }

    companion object {
        fun parse(text: String, role: PaymentRole): BankBootstrap {
            require(text.length in 1..8192) { "Блок настроек пустой или слишком большой" }
            val fields = linkedMapOf<String, String>()
            val pattern = Regex("^\\s*(URL|TLS SHA-256|Код кассы|Код покупателя)\\s*(?::|=)?\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
            for (line in text.lineSequence()) {
                val match = pattern.matchEntire(line) ?: continue
                val name = match.groupValues[1].lowercase()
                require(name !in fields) { "В блоке повторяются параметры. Скопируйте один вывод ./oasis show" }
                fields[name] = match.groupValues[2]
            }
            val label = if (role == PaymentRole.TERMINAL) "код кассы" else "код покупателя"
            return BankBootstrap(requireNotNull(fields["url"]) { "В блоке нет URL" },
                requireNotNull(fields["tls sha-256"]) { "В блоке нет TLS SHA-256" },
                requireNotNull(fields[label]) { "В блоке нет $label" }).validated()
        }
    }
}
