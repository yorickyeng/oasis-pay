package ru.oasispay

import org.junit.Assert.*
import org.junit.Test
import ru.oasispay.data.protocol.*
import ru.oasispay.domain.parseAmount
import ru.oasispay.domain.rubles
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Locale
import kotlin.random.Random

class ProtocolTest {
    private fun key(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun request(terminal: KeyPair, changes: Fields = emptyMap()): Cose = Cose.sign(
        message("payment_request",
            "terminal_id" to terminal.public.encoded.sha256().hex(), "merchant_id" to "merchant",
            "transaction_id" to newId(), "order_id" to newId(), "amount" to 19900L, "currency" to "RUB",
            "nonce" to ByteArray(32), "created_at" to now(), "expires_at" to now() + 120,
            "description" to "Покупка", "terminal_sequence" to 1L, "capabilities" to listOf("ble-gatt/1")) + changes,
        terminal.private, terminal.public,
    )

    private fun result(request: Cose, bank: KeyPair, changes: Fields = emptyMap()): Cose {
        val fields = message("payment_result", "status" to "PAID", "processed_at" to now(),
            "expires_at" to now() + 86400, "server_operation_id" to newId(),
            "phone_device_id" to "a".repeat(64), "result_nonce" to ByteArray(32), "request_hash" to request.hash) +
            bindings.associateWith { request.fields.getValue(it) } + changes
        return Cose.sign(fields, bank.private, bank.public)
    }

    @Test
    fun kotlinVerifiesPythonCoseVector() {
        val vector = javaClass.getResourceAsStream("/bank-cose-vector.txt")!!.bufferedReader().readLines()
        val public = publicKey(vector[0].hexBytes())
        val raw = vector[1].hexBytes()
        val fields = Cose.parse(raw).verified(public)
        assertEquals(19900L, fields.number("amount"))
        assertEquals("RUB", fields.text("currency"))
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Cose.parse(raw).verified(public) }
    }

    @Test
    fun rejectsNonCanonicalUnsupportedAndOversizedCbor() {
        assertEquals("a266616d6f756e74194dbc6863757272656e637963525542",
            Cbor.encode(mapOf("currency" to "RUB", "amount" to 19900)).hex())
        for (invalid in listOf("1801", "0101", "a2616101616102", "9fff", "61ff", "c101",
            "f814", "fb3ff0000000000000", "1b8000000000000000", "9b7fffffffffffffff")) {
            assertThrows(Exception::class.java) { Cbor.decode(invalid.hexBytes()) }
        }
        assertThrows(IllegalArgumentException::class.java) { Cbor.decode(ByteArray(Cbor.LIMIT + 1)) }
        assertThrows(IllegalArgumentException::class.java) { Cbor.encode(ByteArray(Cbor.LIMIT)) }
        assertThrows(IllegalArgumentException::class.java) { Cbor.encode(mapOf<Any, Any>(1 to 1, 1L to 2)) }
        var nested: Any = 0L
        repeat(10) { nested = listOf(nested) }
        assertThrows(IllegalArgumentException::class.java) { Cbor.encode(nested) }
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L)) {
            assertEquals(value, Cbor.decode(Cbor.encode(value)))
        }
    }

    @Test(timeout = 10_000)
    fun boundedFuzzCannotEscapeTheDecoderAsFatalErrors() {
        val random = Random(20491104)
        repeat(1500) {
            val bytes = random.nextBytes(random.nextInt(1, 1025))
            try {
                val decoded = Cbor.decode(bytes)
                assertArrayEquals(bytes, Cbor.encode(decoded))
            } catch (_: Exception) {
                // Malformed input must fail with an ordinary exception, not a VM error or a hang.
            }
        }
        assertThrows(Exception::class.java) { Cbor.decode(ByteArray(1200) { 0x81.toByte() } + byteArrayOf(0)) }
    }

    @Test
    fun encodedMessagesCannotBeMutatedAfterSigning() {
        val terminal = key()
        val signed = request(terminal)
        val bytes = signed.raw
        val hash = signed.hash
        bytes.fill(0)
        hash.fill(0)
        signed.fields.bytes("nonce").fill(1)
        assertEquals(19900L, signed.verified(terminal.public).number("amount"))
        assertArrayEquals(ByteArray(32), signed.fields.bytes("nonce"))
        assertFalse(signed.hash.all { it == 0.toByte() })
        assertThrows(IllegalArgumentException::class.java) { message("x", "type" to "payment_result") }
    }

    @Test
    fun rejectsMalleableEs256AndUnknownProtectedHeaders() {
        val terminal = key()
        val signed = request(terminal)
        val cose = (Cbor.decode(signed.raw) as List<*>).toMutableList()
        val signature = (cose[3] as ByteArray).copyOf()
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        val highS = order.subtract(BigInteger(1, signature.copyOfRange(32, 64))).toByteArray().takeLast(32).toByteArray()
        highS.copyInto(signature, 32)
        cose[3] = signature
        assertThrows(IllegalArgumentException::class.java) { Cose.parse(Cbor.encode(cose.filterNotNull())) }
        cose[3] = (Cbor.decode(signed.raw) as List<*>)[3]
        cose[0] = Cbor.encode(mapOf(1 to -7, 4 to terminal.public.encoded.sha256().copyOf(8), 99 to 1))
        assertThrows(IllegalArgumentException::class.java) {
            Cose.parse(Cbor.encode(cose.filterNotNull())).verified(terminal.public)
        }
    }

    @Test
    fun resultMustMatchAllBindingsTheEnvironmentAndTimeBounds() {
        val terminal = key()
        val bank = key()
        val request = request(terminal)
        assertEquals("PAID", verifyResult(request, result(request, bank), bank.public.encoded).text("status"))
        for ((field, value) in mapOf("amount" to 1L, "nonce" to ByteArray(32) { 1 }, "order_id" to newId(),
            "currency" to "USD", "merchant_id" to "other", "request_hash" to ByteArray(32),
            "expires_at" to now() - 1, "status" to "PROCESSING", "environment" to "production")) {
            assertThrows(Exception::class.java) {
                verifyResult(request, result(request, bank, mapOf(field to value)), bank.public.encoded)
            }
        }
        assertThrows(Exception::class.java) {
            verifyResult(request, result(request, bank), terminal.public.encoded)
        }
        assertEquals("CANCELLED",
            verifyResult(request, result(request, bank, mapOf("status" to "CANCELLED")), bank.public.encoded).text("status"))
    }

    @Test
    fun recoveryPermitMustBindTheExactReceiptAndExpire() {
        val terminal = key()
        val bank = key()
        val request = request(terminal)
        val result = result(request, bank)
        val permitFields = message("result_permit",
            "terminal_id" to request.fields.text("terminal_id"),
            "transaction_id" to request.fields.text("transaction_id"), "phone_device_id" to "a".repeat(64),
            "request_hash" to request.hash, "result_hash" to result.hash, "issued_at" to now(), "expires_at" to now() + 300)
        fun permit(changes: Fields = emptyMap()) = Cose.sign(permitFields + changes, bank.private, bank.public)
        verifyPermit(request, result, permit(), bank.public.encoded)
        for (changes in listOf(mapOf("result_hash" to ByteArray(32)), mapOf("transaction_id" to newId()),
            mapOf("expires_at" to now() - 1), mapOf("expires_at" to now() + 10000))) {
            assertThrows(Exception::class.java) { verifyPermit(request, result, permit(changes), bank.public.encoded) }
        }
        assertThrows(Exception::class.java) {
            verifyPermit(request, result, permit(), bank.public.encoded, moment = now() + 301)
        }
    }

    @Test
    fun registrationRenewalDoesNotInvalidateHistoricalIdentity() {
        val bank = key()
        val device = key()
        val moment = now()
        val original = message("device_certificate", "device_id" to device.public.encoded.sha256().hex(),
            "role" to "terminal", "public_key" to device.public.encoded, "merchant_id" to "merchant",
            "valid_from" to moment - 86400, "valid_to" to moment + 100)
        val fields = verifyCertificate(Cose.sign(original, bank.private, bank.public), bank.public.encoded, "terminal")
        assertTrue(certificateCovers(fields, moment - 100))
        assertFalse(certificateCovers(fields, moment + 101))
        val renewed = original + mapOf("valid_from" to moment, "valid_to" to moment + 86400)
        val fresh = verifyCertificate(Cose.sign(renewed, bank.private, bank.public), bank.public.encoded, "terminal")
        assertFalse(certificateCovers(fresh, moment - 100))
        assertTrue(certificateCovers(fresh, moment))
        assertThrows(Exception::class.java) {
            verifyCertificate(Cose.sign(original, bank.private, bank.public), bank.public.encoded, "buyer")
        }
    }

    @Test
    fun deliveryAcknowledgementCannotChangeItsOperationOrClaimDeclinedGoods() {
        val terminal = key()
        val bank = key()
        val request = request(terminal)
        val result = result(request, bank, mapOf("status" to "DECLINED"))
        val fields = message("payment_result_ack",
            "terminal_id" to request.fields.text("terminal_id"), "transaction_id" to request.fields.text("transaction_id"),
            "result_hash" to result.hash, "terminal_state" to "RECEIVED", "received_at" to now())
        fun ack(changes: Fields = emptyMap()) = Cose.sign(fields + changes, terminal.private, terminal.public)
        verifyAcknowledgement(request, result, ack(), terminal.public)
        for (changes in listOf(mapOf("terminal_state" to "FULFILLED"), mapOf("result_hash" to ByteArray(32)),
            mapOf("transaction_id" to newId()), mapOf("received_at" to now() + 1000))) {
            assertThrows(Exception::class.java) { verifyAcknowledgement(request, result, ack(changes), terminal.public) }
        }
    }

    @Test
    fun shortApdusRoundTripBoundedMessages() {
        val payload = ByteArray(Cbor.LIMIT) { it.toByte() }
        val card = ru.oasispay.data.nfc.Apdu.Endpoint { it.reversedArray() }
        val result = ru.oasispay.data.nfc.Apdu.exchange(payload, card::command)
        assertArrayEquals(payload.reversedArray(), result)
        assertThrows(IllegalArgumentException::class.java) { card.command(byteArrayOf(0)) }
    }

    @Test
    fun moneyUsesIntegerMinorUnitsAndStableFormatting() {
        assertEquals(1L, parseAmount("0,01"))
        assertEquals(19900L, parseAmount("199"))
        assertEquals(19950L, parseAmount("199.5"))
        assertEquals(100000L, parseAmount("1000,00"))
        for (value in listOf("", "0", "-1", "1e2", "1.001", "1000.01", "NaN", "999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { parseAmount(value) }
        }
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("199,50 ₽", rubles(19950))
            assertEquals("5\u00a0000,00 ₽", rubles(500000))
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
