package ru.oasispay.data.protocol

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class SecureIdentity(val certificate: ByteArray, val bankKey: ByteArray, val sign: (Fields) -> Cose)

/** Mutually certified ephemeral P-256 ECDH; transcript-bound HKDF-SHA256 and directional AES-256-GCM. */
internal object SecureHandshake {
    private const val NFC = "nfc-isodep/1"
    private fun ephemeral() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun packet(identity: SecureIdentity, proof: Fields) = Cbor.encode(mapOf(
        "certificate" to identity.certificate, "proof" to identity.sign(proof).raw))

    private data class VerifiedHello(val proof: Fields, val certificate: Fields)
    private fun verified(raw: ByteArray, bankKey: ByteArray, role: String, session: Int): VerifiedHello {
        val envelope = Cbor.decode(raw).fields()
        require(envelope.keys == setOf("certificate", "proof"))
        val certificate = verifyCertificate(Cose.parse(envelope.bytes("certificate")), bankKey, role, now())
        val proof = Cose.parse(envelope.bytes("proof")).verified(publicKey(certificate.bytes("public_key")))
        proof.requireFields(setOf("session", "ephemeral", "nonce", "binding"))
        require(proof.text("type") == if (role == "terminal") "terminal_hello" else "buyer_hello")
        require(proof.number("session") == session.toLong() && proof.bytes("nonce").size == 32 &&
            proof.bytes("binding").size == 32)
        publicKey(proof.bytes("ephemeral"))
        return VerifiedHello(proof, certificate)
    }

    class Terminal(private val identity: SecureIdentity, private val session: Int, orderHash: ByteArray, private val transport: String = NFC) {
        private val pair = ephemeral()
        val hello = packet(identity, message("terminal_hello", "session" to session.toLong(),
            "ephemeral" to pair.public.encoded, "nonce" to randomBytes(32), "binding" to orderHash))
        private var used = false
        var peerDeviceId: String = ""
            private set
        fun complete(client: ByteArray): SecureChannel {
            check(!used)
            val verified = verified(client, identity.bankKey, "buyer", session)
            val proof = verified.proof
            require(proof.bytes("binding").contentEquals(hello.sha256())) { "Подмена сеанса кассы" }
            used = true
            peerDeviceId = verified.certificate.text("device_id")
            return derive(pair, proof.bytes("ephemeral"), hello, client, terminal = true, transport = transport)
        }
    }

    data class Buyer(val hello: ByteArray, val channel: SecureChannel, val orderHash: ByteArray, val terminalId: String)
    fun buyer(identity: SecureIdentity, server: ByteArray, session: Int, transport: String = NFC): Buyer {
        val verified = verified(server, identity.bankKey, "terminal", session)
        val proof = verified.proof
        val pair = ephemeral()
        val client = packet(identity, message("buyer_hello", "session" to session.toLong(),
            "ephemeral" to pair.public.encoded, "nonce" to randomBytes(32), "binding" to server.sha256()))
        return Buyer(client, derive(pair, proof.bytes("ephemeral"), server, client, terminal = false, transport = transport),
            proof.bytes("binding"), verified.certificate.text("device_id"))
    }

    private fun derive(pair: KeyPair, peer: ByteArray, server: ByteArray, client: ByteArray, terminal: Boolean, transport: String): SecureChannel {
        val label = "oasis-pay/$transport"
        val transcript = Cbor.encode(listOf(label, server, client)).sha256()
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(pair.private); doPhase(publicKey(peer), true); generateSecret()
        }
        val prk = hmac(transcript, shared)
        shared.fill(0)
        val material = ByteArray(88)
        var previous = byteArrayOf()
        var offset = 0
        for (index in 1..3) {
            previous = hmac(prk, previous + label.toByteArray(Charsets.US_ASCII) + byteArrayOf(index.toByte()))
            val count = minOf(previous.size, material.size - offset)
            previous.copyInto(material, offset, 0, count)
            offset += count
        }
        prk.fill(0)
        previous.fill(0)
        val serverKey = material.copyOfRange(0, 32)
        val buyerKey = material.copyOfRange(32, 64)
        val serverIv = material.copyOfRange(64, 76)
        val buyerIv = material.copyOfRange(76, 88)
        material.fill(0)
        return if (terminal) SecureChannel(serverKey, buyerKey, serverIv, buyerIv, transcript)
        else SecureChannel(buyerKey, serverKey, buyerIv, serverIv, transcript)
    }

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
}

internal class SecureChannel(
    private val outboundKey: ByteArray, private val inboundKey: ByteArray,
    private val outboundIv: ByteArray, private val inboundIv: ByteArray, private val transcript: ByteArray,
) : AutoCloseable {
    private var sent = 0L
    private var received = 0L
    private var closed = false
    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, sequence: Long, kind: Int): Cipher {
        val nonce = iv.copyOf()
        val counter = ByteBuffer.allocate(8).putLong(sequence).array()
        for (i in counter.indices) nonce[i + 4] = (nonce[i + 4].toInt() xor counter[i].toInt()).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(transcript + ByteBuffer.allocate(12).putInt(kind).putLong(sequence).array())
        }
    }
    fun seal(kind: Int, bytes: ByteArray): ByteArray {
        check(!closed && sent < 1_000_000)
        require(kind in 1..4 && bytes.size in 1..(Cbor.LIMIT - 64))
        val encrypted = cipher(Cipher.ENCRYPT_MODE, outboundKey, outboundIv, sent, kind).doFinal(bytes)
        return Cbor.encode(mapOf("sequence" to sent++, "ciphertext" to encrypted))
    }
    fun open(kind: Int, raw: ByteArray): ByteArray {
        check(!closed && received < 1_000_000)
        require(kind in 1..4)
        val envelope = Cbor.decode(raw).fields()
        require(envelope.keys == setOf("sequence", "ciphertext") && envelope.number("sequence") == received) {
            "Нарушен порядок защищённых сообщений"
        }
        val result = cipher(Cipher.DECRYPT_MODE, inboundKey, inboundIv, received, kind)
            .doFinal(envelope.bytes("ciphertext"))
        received++
        return result
    }
    override fun close() {
        closed = true
        outboundKey.fill(0); inboundKey.fill(0); outboundIv.fill(0); inboundIv.fill(0)
    }
}
