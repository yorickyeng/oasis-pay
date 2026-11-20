package ru.oasispay.data.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

internal const val VERSION = "offline-relay/1"
internal const val ENVIRONMENT = "sandbox"
internal val bindings = listOf("terminal_id", "transaction_id", "order_id", "amount", "currency", "nonce", "merchant_id")
internal fun now() = System.currentTimeMillis() / 1000
private val secureRandom = SecureRandom()
internal fun randomBytes(size: Int) = ByteArray(size).also(secureRandom::nextBytes)
internal fun newId() = UUID.randomUUID().toString().replace("-", "")
internal fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun ByteArray.b64() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
internal fun String.unb64() = Base64.getUrlDecoder().decode(this)
internal fun message(kind: String, vararg fields: Pair<String, Any>): Fields {
    require(fields.map { it.first }.distinct().size == fields.size &&
        fields.none { it.first in setOf("type", "protocol_version", "environment") })
    return mapOf("type" to kind, "protocol_version" to VERSION, "environment" to ENVIRONMENT, *fields)
}

internal fun publicKey(raw: ByteArray): PublicKey {
    require(raw.size == 91)
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw)) as ECPublicKey
    val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    require(key.params.curve == parameters.curve && key.params.generator == parameters.generator &&
        key.params.order == parameters.order && key.params.cofactor == parameters.cofactor &&
        key.encoded.contentEquals(raw))
    return key
}

internal class Cose private constructor(
    private val encoded: ByteArray,
    private val headers: ByteArray,
    private val data: ByteArray,
    private val signature: ByteArray,
) {
    val raw get() = encoded.copyOf()
    private val digest by lazy { encoded.sha256() }
    val hash get() = digest.copyOf()
    val signingKeyId get() = ((Cbor.decode(headers) as Map<*, *>)[4L] as ByteArray).copyOf()
    val fields: Fields get() {
        return Cbor.decode(data).fields().also {
            require(it.text("protocol_version") == VERSION && it.text("environment") == ENVIRONMENT)
        }
    }

    fun verified(key: PublicKey): Fields {
        require(headers.contentEquals(protected(key))) { "Неизвестный ключ подписи" }
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key)
            update(signatureInput(headers, data))
        }
        require(verifier.verify(rawToDer(signature))) { "Подпись сообщения недействительна" }
        return fields
    }

    companion object {
        private val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        private val halfOrder = order.shiftRight(1)
        fun parse(raw: ByteArray): Cose {
            val array = Cbor.decode(raw) as? List<*> ?: error("Ожидался объект COSE")
            require(array.size == 4 && array[1] == emptyMap<Any, Any>())
            val signature = array[3] as ByteArray
            require(signature.size == 64)
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            require(r.signum() > 0 && r < order && s.signum() > 0 && s <= halfOrder) {
                "Неканоническая подпись ES256"
            }
            return Cose(raw.copyOf(), array[0] as ByteArray, array[2] as ByteArray, signature)
        }

        fun sign(fields: Fields, key: PrivateKey, publicKey: PublicKey): Cose {
            val headers = protected(publicKey)
            val data = Cbor.encode(fields)
            val der = Signature.getInstance("SHA256withECDSA").apply {
                initSign(key)
                update(signatureInput(headers, data))
            }.sign()
            val signature = derToRaw(der)
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (s > halfOrder) {
                val canonical = order.subtract(s).toByteArray()
                signature.fill(0, 32)
                canonical.copyInto(signature, 64 - canonical.size)
            }
            return Cose(Cbor.encode(listOf(headers, emptyMap<Int, Int>(), data, signature)), headers, data, signature)
        }

        private fun protected(key: PublicKey) = Cbor.encode(mapOf(1 to -7, 4 to key.encoded.sha256().copyOf(8)))

        private fun signatureInput(headers: ByteArray, data: ByteArray) =
            Cbor.encode(listOf("Signature1", headers, byteArrayOf(), data))

        private fun derToRaw(der: ByteArray): ByteArray {
            require(der.size in 8..72 && der[0] == 48.toByte() && der[1].toInt() == der.size - 2)
            var offset = 2
            val result = ByteArray(64)
            repeat(2) { index ->
                require(der[offset++] == 2.toByte())
                val count = der[offset++].toInt()
                require(count in 1..33 && offset + count <= der.size && der[offset] >= 0)
                val skip = if (count > 1 && der[offset] == 0.toByte()) 1 else 0
                require(count - skip <= 32)
                der.copyInto(result, (index + 1) * 32 - count + skip, offset + skip, offset + count)
                offset += count
            }
            require(offset == der.size)
            return result
        }

        private fun rawToDer(raw: ByteArray): ByteArray {
            fun integer(bytes: ByteArray): ByteArray {
                val first = bytes.indexOfFirst { it != 0.toByte() }.takeIf { it >= 0 } ?: bytes.lastIndex
                val value = bytes.copyOfRange(first, bytes.size)
                val positive = if (value[0] < 0) byteArrayOf(0) + value else value
                return byteArrayOf(2, positive.size.toByte()) + positive
            }
            val integers = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(48, integers.size.toByte()) + integers
        }
    }
}

internal fun verifyResult(request: Cose, result: Cose, bankKey: ByteArray, checkExpiry: Boolean = true): Fields {
    validatePaymentRequest(request.fields)
    val fields = result.verified(publicKey(bankKey))
    fields.requireFields(setOf("terminal_id", "merchant_id", "transaction_id", "order_id", "amount", "currency",
        "nonce", "status", "server_operation_id", "phone_device_id", "request_hash", "processed_at",
        "result_nonce", "expires_at"))
    require(fields.text("type") == "payment_result")
    for (name in bindings) {
        val original = request.fields.getValue(name)
        val received = fields.getValue(name)
        require(if (original is ByteArray) received is ByteArray && original.contentEquals(received)
                else original == received) { "Результат относится к другому счёту" }
    }
    require(fields.bytes("request_hash").contentEquals(request.hash))
    require(fields.bytes("result_nonce").size == 32)
    fields.requireDevice("phone_device_id")
    fields.requireIdentifier("server_operation_id")
    val processed = fields.number("processed_at")
    val expires = fields.number("expires_at")
    require(processed in (request.fields.number("created_at") - 30)..(now() + 30))
    require(processed >= 0 && expires > processed && expires - processed <= RESULT_LIFETIME && (!checkExpiry || expires >= now())) { "Срок действия результата истёк" }
    require(fields.text("status") in setOf("PAID", "DECLINED", "CANCELLED"))
    return fields
}
