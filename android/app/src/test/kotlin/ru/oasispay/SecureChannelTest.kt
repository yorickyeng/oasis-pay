package ru.oasispay

import org.junit.Assert.*
import org.junit.Test
import ru.oasispay.data.protocol.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SecureChannelTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val bank = pair()
    private fun identity(role: String, otherBank: Boolean = false, expired: Boolean = false): SecureIdentity {
        val key = pair()
        val issuer = if (otherBank) pair() else bank
        val now = now()
        val cert = Cose.sign(message("device_certificate", "device_id" to key.public.encoded.sha256().hex(),
            "role" to role, "public_key" to key.public.encoded, "merchant_id" to "demo-merchant",
            "valid_from" to now - 100, "valid_to" to if (expired) now - 1 else now + 1000), issuer.private, issuer.public)
        return SecureIdentity(cert.raw, bank.public.encoded) { Cose.sign(it, key.private, key.public) }
    }

    @Test fun bidirectionalExchangeRejectsReplayAndDirectionReflection() {
        val server = SecureHandshake.Terminal(identity("terminal"), 42, ByteArray(32) { 7 })
        val buyer = SecureHandshake.buyer(identity("buyer"), server.hello, 42)
        server.complete(buyer.hello).use { terminal -> buyer.channel.use { client ->
            assertArrayEquals(ByteArray(32) { 7 }, buyer.orderHash)
            val packet = terminal.seal(3, "invoice".toByteArray())
            assertArrayEquals("invoice".toByteArray(), client.open(3, packet))
            assertThrows(Exception::class.java) { client.open(3, packet) }
            val result = client.seal(2, "result".toByteArray())
            assertThrows(Exception::class.java) { client.open(2, result) }
            assertArrayEquals("result".toByteArray(), terminal.open(2, result))
            assertArrayEquals("ack".toByteArray(), client.open(3, terminal.seal(3, "ack".toByteArray())))
            assertArrayEquals("handoff".toByteArray(), terminal.open(4, client.seal(4, "handoff".toByteArray())))
        } }
        assertThrows(Exception::class.java) { server.complete(buyer.hello) }
    }

    @Test fun tamperingWrongMessageKindAndOldSessionAreRejected() {
        val owner = identity("terminal")
        val customer = identity("buyer")
        val server = SecureHandshake.Terminal(owner, 1, ByteArray(32))
        val buyer = SecureHandshake.buyer(customer, server.hello, 1)
        server.complete(buyer.hello).use { terminal -> buyer.channel.use { client ->
            val packet = terminal.seal(3, byteArrayOf(1, 2, 3))
            val envelope = Cbor.decode(packet).fields().toMutableMap()
            val cipher = envelope.bytes("ciphertext").copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            envelope["ciphertext"] = cipher
            assertThrows(Exception::class.java) { client.open(3, Cbor.encode(envelope)) }
            assertThrows(Exception::class.java) { client.open(2, packet) }
            assertArrayEquals(byteArrayOf(1, 2, 3), client.open(3, packet))
            val next = SecureHandshake.Terminal(owner, 1, ByteArray(32))
            val nextBuyer = SecureHandshake.buyer(customer, next.hello, 1)
            next.complete(nextBuyer.hello).close()
            nextBuyer.channel.use { assertThrows(Exception::class.java) { it.open(3, packet) } }
        } }
    }

    @Test fun certificatesRolesAndTranscriptAreAuthenticated() {
        val server = SecureHandshake.Terminal(identity("terminal"), 1, ByteArray(32))
        assertThrows(Exception::class.java) { SecureHandshake.buyer(identity("buyer"), server.hello, 2) }
        val rogue = SecureHandshake.Terminal(identity("terminal", otherBank = true), 1, ByteArray(32))
        assertThrows(Exception::class.java) { SecureHandshake.buyer(identity("buyer"), rogue.hello, 1) }
        val buyer = SecureHandshake.buyer(identity("buyer", expired = true), server.hello, 1)
        buyer.channel.close()
        assertThrows(Exception::class.java) { server.complete(buyer.hello) }
        val another = SecureHandshake.Terminal(identity("terminal"), 1, ByteArray(32))
        val legitimate = SecureHandshake.buyer(identity("buyer"), another.hello, 1)
        legitimate.channel.close()
        assertThrows(Exception::class.java) { server.complete(legitimate.hello) }
        val wrongRole = SecureHandshake.buyer(identity("terminal"), server.hello, 1)
        wrongRole.channel.close()
        assertThrows(Exception::class.java) { server.complete(wrongRole.hello) }
    }
}
