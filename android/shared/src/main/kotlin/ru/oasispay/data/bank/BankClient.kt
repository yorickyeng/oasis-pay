package ru.oasispay.data.bank

import android.annotation.SuppressLint
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import ru.oasispay.data.protocol.Cbor
import ru.oasispay.data.protocol.Cose
import ru.oasispay.data.protocol.DeviceKey
import ru.oasispay.data.protocol.Fields
import ru.oasispay.data.protocol.bytes
import ru.oasispay.data.protocol.fields
import ru.oasispay.data.protocol.hex
import ru.oasispay.data.protocol.message
import ru.oasispay.data.protocol.number
import ru.oasispay.data.protocol.now
import ru.oasispay.data.protocol.sha256
import ru.oasispay.data.protocol.text
import ru.oasispay.data.protocol.verifyCertificate
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

internal class BankRejectedException(val code: String) : IOException("Bank request rejected")

/** Each request has a total deadline and is cancelled when its calling coroutine is cancelled. */
internal class BankClient(address: String, pin: String, private val token: String) {
    private val endpoint = endpoint(address)
    private val fingerprint = fingerprint(pin)
    init { require(token.matches(Regex("(?:[A-Za-z0-9_-]{32}|[0-9a-f]{64})"))) { "Проверьте код подключения" } }
    private val client = clientFor(fingerprint)

    suspend fun post(path: String, body: ByteArray): Fields {
        require(body.size in 1..Cbor.LIMIT)
        require(path.matches(Regex("[a-z0-9/-]{1,128}")) && !path.contains("//"))
        val url = endpoint.newBuilder().encodedPath("/api/v1/$path").build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/cbor")
            .post(body.toRequestBody(CBOR))
            .build()
        return execute(client, request)
    }

    companion object {
        internal var wifiSocketFactory: (String) -> javax.net.SocketFactory? = { null }

        private fun endpoint(address: String) = address.trim().trimEnd('/').toHttpUrl().also {
            require(it.isHttps && it.host.isNotBlank() && it.username.isEmpty() && it.password.isEmpty() &&
                it.encodedPath == "/" && it.query == null && it.fragment == null) { "Нужен HTTPS-адрес банка" }
        }

        private fun fingerprint(pin: String) = pin.filterNot { it.isWhitespace() || it == ':' }.lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Введите SHA-256 отпечаток из консоли банка" }
        }

        suspend fun checkConnection(address: String, pin: String) {
            val url = endpoint(address).newBuilder().encodedPath("/health").build()
            val reply = execute(clientFor(fingerprint(pin)), Request.Builder().url(url).get().build())
            val serverTime = reply["server_time"] as? Long
            if (serverTime != null && kotlin.math.abs(serverTime - now()) > ru.oasispay.data.protocol.CLOCK_SKEW) {
                throw ru.oasispay.data.protocol.BankClockException()
            }
            check(reply["status"] == "ok") { "Банк временно недоступен" }
        }

        private suspend fun execute(client: OkHttpClient, request: Request): Fields {
            val sockets = wifiSocketFactory(request.url.host)
            val routed = if (sockets == null) client else client.newBuilder().socketFactory(sockets).build()
            return suspendCancellableCoroutine { continuation ->
                val call = routed.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            Result.success(response.use {
                                require(it.header("Content-Type")?.substringBefore(';')?.trim() == "application/cbor") {
                                    "Банк вернул неподдерживаемый формат"
                                }
                                val reply = Cbor.decode(it.body.byteStream().use { stream ->
                                    stream.readNBytes(Cbor.LIMIT + 1)
                                }).fields()
                                if (it.code != 200) {
                                    throw BankRejectedException(reply["error"] as? String ?: "invalid_response")
                                }
                                reply
                            })
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                })
            }
        }

        private val CBOR = "application/cbor".toMediaType()
        private val pool = ConnectionPool(2, 5, TimeUnit.MINUTES)
        private val dispatcher = Dispatcher().apply {
            maxRequests = 8
            maxRequestsPerHost = 4
        }
        private val clients = object : LinkedHashMap<String, OkHttpClient>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?) = size > 4
        }

        @Synchronized
        private fun clientFor(pin: String): OkHttpClient = clients.getOrPut(pin) {
            val trust = PinnedTrustManager(pin)
            val tls = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
            OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, trust)
                .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3).build()))
                .connectionPool(pool)
                .dispatcher(dispatcher)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * The out-of-band fingerprint bootstraps the self-hosted bank's trust anchor.
     * PKIX validation is delegated to the platform. OkHttp verifies the hostname.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinnedTrustManager(pin: String) : X509TrustManager {
        private val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are unsupported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Missing bank certificate")
            leaf.checkValidity()
            if (!MessageDigest.isEqual(leaf.encoded.sha256(), expected)) {
                throw CertificateException("Bank fingerprint differs")
            }
            val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("bank", leaf)
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(anchors)
            }
            val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            platform.checkServerTrusted(chain, authType)
        }
    }
}

internal suspend fun enroll(role: String, address: String, pin: String, code: String, key: DeviceKey): Fields {
    val public = key.createIfNeeded()
    val reply = BankClient(address, pin, code.trim()).post(
        "enroll/$role", key.sign(message("enroll", "role" to role, "public_key" to public,
            "device_model" to Build.MODEL.take(80).ifBlank { "Android" })).raw,
    )
    val fields = verifyCertificate(Cose.parse(reply.bytes("certificate")), reply.bytes("bank_key"), role, at = now())
    require(fields.text("type") == "device_certificate" && fields.text("role") == role &&
        fields.text("device_id") == public.sha256().hex() && fields.bytes("public_key").contentEquals(public) &&
        fields.number("valid_from") <= now() + 30 && fields.number("valid_to") >= now())
    return reply
}
