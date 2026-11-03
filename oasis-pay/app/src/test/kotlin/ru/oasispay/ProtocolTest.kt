package ru.oasispay

import ru.oasispay.data.nfc.Apdu
import ru.oasispay.data.nfc.Upload
import ru.oasispay.domain.parseAmount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun moneyUsesIntegerKopecks() {
        assertEquals(19950L, parseAmount("199,50"))
        assertEquals(100L, parseAmount("1"))
        assertEquals(110L, parseAmount("1.1"))
        assertThrows(IllegalArgumentException::class.java) { parseAmount("0,001") }
        assertThrows(IllegalArgumentException::class.java) { parseAmount("1000,01") }
    }

    @Test
    fun uploadRejectsOutOfOrderAndOversizeFragments() {
        val upload = Upload()
        assertThrows(IllegalArgumentException::class.java) { upload.append(1, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { upload.append(0, ByteArray(Apdu.CHUNK + 1)) }
        upload.append(0, "hello".toByteArray())
        upload.append(5, " world".toByteArray())
        assertEquals("hello world", upload.read())
    }

    @Test
    fun twoTapsExchangeFragmentsAndReturnAck() {
        val order = "a".repeat(670)
        val result = "b".repeat(440)
        val ack = "c".repeat(260)
        var cardResult = ""
        var receivedAck = ""
        var upload = Upload()
        val card: (ByteArray) -> ByteArray = { command ->
            val offset = ((command[2].toInt() and 255) shl 8) or (command[3].toInt() and 255)
            when (command[1].toInt()) {
                Apdu.BEGIN_REQUEST, Apdu.BEGIN_ACK -> upload = Upload()
                Apdu.APPEND -> upload.append(offset, command.copyOfRange(5, command.size))
                Apdu.END_REQUEST -> assertEquals(order, upload.read())
                Apdu.END_ACK -> receivedAck = upload.read()
            }
            val reply = if (command[1].toInt() == Apdu.READ_RESULT) {
                cardResult.toByteArray().copyOfRange(offset, minOf(cardResult.length, offset + Apdu.CHUNK))
            } else byteArrayOf()
            reply + Apdu.ok
        }
        assertFalse(Apdu.exchange(order, card) { error("No result on first tap") })
        cardResult = result
        assertTrue(Apdu.exchange(order, card) {
            assertEquals(result, it)
            ack
        })
        assertEquals(ack, receivedAck)
    }
}
