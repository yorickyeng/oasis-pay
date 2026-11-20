package ru.oasispay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.oasispay.data.bank.BankClient
import ru.oasispay.data.bank.BankRejectedException
import ru.oasispay.data.protocol.Cbor
import ru.oasispay.data.protocol.Fields
import ru.oasispay.data.protocol.hex
import ru.oasispay.data.protocol.sha256
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class BankClientTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    private fun certificate(host: String = "127.0.0.1", expired: Boolean = false): HeldCertificate {
        val builder = HeldCertificate.Builder().commonName("oasis test bank").addSubjectAlternativeName(host)
        if (expired) builder.validityInterval(System.currentTimeMillis() - 100_000, System.currentTimeMillis() - 1_000)
        return builder.build()
    }

    private fun server(
        certificate: HeldCertificate = certificate(),
        protocols: Array<String> = arrayOf("TLSv1.3"),
        handler: (RecordedRequest) -> MockResponse,
    ): Pair<String, String> {
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer().apply {
            this.protocols = listOf(Protocol.HTTP_1_1)
            useHttps(ProtocolSocketFactory(tls.sslSocketFactory(), protocols))
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = handler(request)
            }
        }
        servers += server
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url("/").newBuilder().host("127.0.0.1").build().toString() to
            certificate.certificate.encoded.sha256().hex()
    }

    private fun reply(fields: Fields, code: Int = 200) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/cbor").body(Buffer().write(Cbor.encode(fields))).build()

    @Test
    fun healthProbeUsesPinnedTlsWithoutCredentials(): Unit = runBlocking {
        val (url, pin) = server { request ->
            assertEquals("GET", request.method)
            assertEquals("/health", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
            reply(mapOf("status" to "ok"))
        }
        BankClient.checkConnection(url, pin)
        expectIo { BankClient.checkConnection(url, "0".repeat(64)) }
    }

    @Test
    fun verifiesPinnedCertificateAndReturnsBoundedCbor(): Unit = runBlocking {
        val (url, pin) = server { request ->
            assertEquals("/api/v1/payments", request.url.encodedPath)
            assertEquals("Bearer " + "a".repeat(64), request.headers["Authorization"])
            assertEquals("application/cbor", request.headers["Content-Type"])
            assertEquals(TlsVersion.TLS_1_3, request.handshake?.tlsVersion)
            assertEquals(mapOf("amount" to 19900L), Cbor.decode(requireNotNull(request.body).toByteArray()))
            reply(mapOf("status" to "ok"))
        }
        val response = BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(mapOf("amount" to 19900L)))
        assertEquals(mapOf("status" to "ok"), response)
    }

    @Test
    fun rejectsWrongPinExpiredCertificateHostnameAndTls12(): Unit = runBlocking {
        val healthy = server { reply(mapOf("status" to "ok")) }
        expectIo { BankClient(healthy.first, "0".repeat(64), "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>())) }
        for ((cert, protocols) in listOf(
            certificate("other.invalid") to arrayOf("TLSv1.3"),
            certificate(expired = true) to arrayOf("TLSv1.3"),
            certificate() to arrayOf("TLSv1.2"),
        )) {
            val (url, pin) = server(cert, protocols) { reply(mapOf("status" to "ok")) }
            expectIo { BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>())) }
        }
    }

    @Test
    fun rejectsCleartextCredentialsAndUnexpectedResponseTypes(): Unit = runBlocking {
        for (url in listOf("http://127.0.0.1:1234", "https://user:secret@127.0.0.1", "https://127.0.0.1/path")) {
            assertThrows(IllegalArgumentException::class.java) { BankClient(url, "a".repeat(64), "a".repeat(64)) }
        }
        val (url, pin) = server { MockResponse.Builder().setHeader("Content-Type", "text/html").body("{}").build() }
        try {
            BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>()))
            fail("Unexpected response type was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun redirectsDoNotForwardAuthorizationAndOversizedBodiesAreRejected(): Unit = runBlocking {
        val requests = AtomicInteger()
        val (url, pin) = server {
            requests.incrementAndGet()
            MockResponse.Builder().code(302).setHeader("Location", "/credentials")
                .setHeader("Content-Type", "application/cbor")
                .body(Buffer().write(Cbor.encode(mapOf("error" to "redirect")))).build()
        }
        try {
            BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>()))
            fail("Redirect was accepted")
        } catch (_: BankRejectedException) {
        }
        assertEquals(1, requests.get())
        val large = server {
            MockResponse.Builder().setHeader("Content-Type", "application/cbor")
                .body(Buffer().write(ByteArray(Cbor.LIMIT + 1))).build()
        }
        try {
            BankClient(large.first, large.second, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>()))
            fail("Oversized body was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test(timeout = 10_000)
    fun cancellingTheCallerCancelsTheNetworkRequest(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (url, pin) = server {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            reply(mapOf("status" to "ok"))
        }
        try {
            val request = async {
                BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>()))
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val start = System.nanoTime()
            request.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 23_000)
    fun totalDeadlineStopsAResponseThatKeepsSendingBytes(): Unit = runBlocking {
        val (url, pin) = server {
            MockResponse.Builder().setHeader("Content-Type", "application/cbor")
                .body(Buffer().write(ByteArray(100))).throttleBody(1, 1, TimeUnit.SECONDS).build()
        }
        val start = System.nanoTime()
        expectIo { BankClient(url, pin, "a".repeat(64)).post("payments", Cbor.encode(emptyMap<String, Any>())) }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Total deadline was not enforced: $elapsed ms", elapsed in 13_000..20_000)
    }

    private suspend fun expectIo(action: suspend () -> Unit) {
        try {
            action()
            fail("Invalid TLS connection was accepted")
        } catch (_: IOException) {
        }
    }

    /** Restricts the actual server handshake, including the negative TLS-1.2-only test. */
    private class ProtocolSocketFactory(
        private val delegate: SSLSocketFactory,
        private val protocols: Array<String>,
    ) : SSLSocketFactory() {
        private fun restrict(socket: Socket) = (socket as SSLSocket).apply { enabledProtocols = protocols }
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(socket: Socket, host: String, port: Int, close: Boolean): Socket =
            restrict(delegate.createSocket(socket, host, port, close))
        override fun createSocket(host: String, port: Int): Socket = restrict(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            restrict(delegate.createSocket(host, port, local, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = restrict(delegate.createSocket(host, port))
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            restrict(delegate.createSocket(host, port, local, localPort))
    }
}
