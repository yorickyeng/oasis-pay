package ru.oasispay.data.nfc

import ru.oasispay.data.protocol.MAX_WIRE
import java.io.ByteArrayOutputStream

internal object Apdu {
    private val aid = byteArrayOf(0xF0.toByte(), 0x4F, 0x41, 0x53, 0x49, 0x53, 0x01)
    val select = byteArrayOf(0, 0xA4.toByte(), 4, 0, aid.size.toByte()) + aid + byteArrayOf(0)
    val ok = byteArrayOf(0x90.toByte(), 0)
    val invalid = byteArrayOf(0x6A, 0x80.toByte())
    val unavailable = byteArrayOf(0x69, 0x85.toByte())
    const val BEGIN_REQUEST = 0x10
    const val APPEND = 0x11
    const val END_REQUEST = 0x12
    const val READ_RESULT = 0x13
    const val BEGIN_ACK = 0x14
    const val END_ACK = 0x15
    const val CHUNK = 220

    fun isSelect(command: ByteArray): Boolean =
        command.contentEquals(select) || command.contentEquals(select.copyOf(select.size - 1))

    fun command(ins: Int, offset: Int = 0, data: ByteArray = byteArrayOf()): ByteArray {
        require(offset in 0..MAX_WIRE && data.size <= CHUNK)
        return byteArrayOf(0x80.toByte(), ins.toByte(), (offset shr 8).toByte(), offset.toByte(),
            data.size.toByte()) + data
    }

    fun exchange(order: String, send: (ByteArray) -> ByteArray, accept: (String) -> String): Boolean {
        fun call(command: ByteArray): ByteArray {
            val reply = send(command)
            require(reply.size in 2..(CHUNK + 2) &&
                reply.takeLast(2).toByteArray().contentEquals(ok)) {
                "Откройте роль покупателя и снова поднесите телефоны"
            }
            return reply.copyOf(reply.size - 2)
        }

        fun upload(value: String, begin: Int, end: Int) {
            val bytes = value.toByteArray()
            require(bytes.size in 1..MAX_WIRE)
            call(command(begin))
            for (offset in bytes.indices step CHUNK) {
                call(command(APPEND, offset, bytes.copyOfRange(offset, minOf(offset + CHUNK, bytes.size))))
            }
            call(command(end))
        }

        call(select)
        upload(order, BEGIN_REQUEST, END_REQUEST)
        val data = ByteArrayOutputStream()
        do {
            val part = call(command(READ_RESULT, data.size()))
            require(data.size() + part.size <= MAX_WIRE)
            data.write(part)
        } while (part.size == CHUNK)
        if (data.size() == 0) return false
        val acknowledgement = accept(data.toByteArray().decodeToString(throwOnInvalidSequence = true))
        upload(acknowledgement, BEGIN_ACK, END_ACK)
        return true
    }
}

internal class Upload {
    private val bytes = ByteArrayOutputStream()

    fun append(offset: Int, data: ByteArray) {
        require(offset == bytes.size() && data.size in 1..Apdu.CHUNK &&
            bytes.size() + data.size <= MAX_WIRE)
        bytes.write(data)
    }

    fun read(): String {
        require(bytes.size() > 0)
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}
