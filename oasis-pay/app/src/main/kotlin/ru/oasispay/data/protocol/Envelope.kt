package ru.oasispay.data.protocol

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val MAX_WIRE = 4096
private val signatureDomain = "oasis-pay/v1\n".encodeToByteArray()

internal fun json(vararg pairs: Pair<String, Any>) = JSONObject(mapOf(*pairs))
internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
internal fun randomHex(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes).hex()
internal fun now() = System.currentTimeMillis() / 1000
internal data class Envelope(val payload: ByteArray, val signature: ByteArray) {
    val value: JSONObject get() = JSONObject(payload.decodeToString(throwOnInvalidSequence = true))
    val hash: String get() = payload.sha256().hex()

    fun encode() = json("payload" to b64(payload), "signature" to b64(signature)).toString()

    fun verify(publicKey: String) {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(publicKey)))
        val valid = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key)
            update(signatureDomain)
            update(payload)
        }.verify(signature)
        require(valid) { "Подпись банка недействительна" }
    }

    companion object {
        fun decode(raw: String): Envelope {
            require(raw.length <= MAX_WIRE)
            val envelope = JSONObject(raw)
            require(envelope.length() == 2)
            return Envelope(unb64(envelope.getString("payload")), unb64(envelope.getString("signature")))
                .also { require(it.payload.size in 1..3000 && it.signature.size in 8..80) }
        }

        fun sign(payload: JSONObject, key: PrivateKey): Envelope {
            val bytes = payload.toString().toByteArray()
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(key)
                update(signatureDomain)
                update(bytes)
            }.sign()
            return Envelope(bytes, signature)
        }
    }
}
