package ru.oasispay.data.bank

import org.json.JSONObject
import ru.oasispay.data.protocol.hex
import ru.oasispay.data.protocol.sha256
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal class BankClient(address: String, pin: String, private val code: String) {

    private val endpoint = URL(address.trim().trimEnd('/')).also {
        require(it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null &&
            it.path.isEmpty() && it.query == null && it.ref == null) { "Нужен адрес вида https://192.168.1.10:8443" }
    }
    private val fingerprint = pin.filterNot { it.isWhitespace() || it == ':' }.lowercase().also {
        require(Regex("[0-9a-f]{64}").matches(it)) { "Введите SHA-256 отпечаток из консоли банка" }
        require(Regex("[A-Za-z0-9_-]{32}").matches(code)) { "Проверьте код из консоли банка" }
    }

    private val tls = SSLContext.getInstance("TLSv1.3").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are unsupported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing bank certificate")
                leaf.checkValidity()
                if (!MessageDigest.isEqual(leaf.encoded.sha256().hex().toByteArray(), fingerprint.toByteArray())) {
                    throw CertificateException("Отпечаток сертификата банка отличается")
                }
            }
        }), null)
    }

    fun post(path: String, body: String): JSONObject {
        val connection = URL(endpoint, "/v1/$path").openConnection() as HttpsURLConnection
        return try {
            connection.sslSocketFactory = tls.socketFactory
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $code")
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val data = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(2048)
                val output = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    check(output.length <= 8192)
                }
                output.toString()
            } ?: "{}"
            val reply = JSONObject(data)
            check(status == 200) {
                when (reply.optString("error")) {
                    "unauthorized" -> "Банк отклонил код доступа"
                    "request_expired_or_clock_skew" -> "Запрос просрочен или часы устройств расходятся"
                    "unknown_terminal" -> "Терминал не зарегистрирован в этом банке"
                    "invalid_signature" -> "Банк отклонил подпись терминала"
                    else -> "Банк вернул ошибку $status (${reply.optString("error", "unknown")})"
                }
            }
            reply
        } finally {
            connection.disconnect()
        }
    }
}
