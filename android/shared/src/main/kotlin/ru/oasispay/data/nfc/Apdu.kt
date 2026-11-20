package ru.oasispay.data.nfc

import java.io.ByteArrayOutputStream

/** Short ISO 7816 APDUs. P1 marks the final fragment, SW=6100 requests another read. */
internal object Apdu {
    val select = byteArrayOf(0, 0xA4.toByte(), 4, 0, 7, 0xF0.toByte(), 0x4F, 0x41, 0x53, 0x49, 0x53, 1, 0)
    val ok = byteArrayOf(0x90.toByte(), 0)
    private val more = byteArrayOf(0x61, 0)
    private val read = byteArrayOf(0x80.toByte(), 0x30, 0, 0, 0)
    private const val CHUNK = 220
    private const val LIMIT = 17_000

    fun exchange(payload: ByteArray, send: (ByteArray) -> ByteArray): ByteArray {
        require(payload.size in 1..LIMIT)
        var response = byteArrayOf()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + CHUNK, payload.size)
            val last = end == payload.size
            val command = byteArrayOf(0x80.toByte(), 0x20, if (last) 1 else 0, 0, (end-offset).toByte()) +
                payload.copyOfRange(offset, end) + byteArrayOf(0)
            response = send(command)
            if (!last) require(response.contentEquals(ok))
            offset = end
        }
        val output = ByteArrayOutputStream()
        while (true) {
            require(response.size in 2..CHUNK+2)
            val length = response.size - 2
            require(output.size() + length <= LIMIT)
            output.write(response, 0, length)
            val status = response.copyOfRange(length, response.size)
            if (status.contentEquals(ok)) return output.toByteArray()
            require(status.contentEquals(more) && length == CHUNK)
            response = send(read)
        }
    }

    class Endpoint(private val handle: (ByteArray) -> ByteArray) {
        private val input = ByteArrayOutputStream()
        private var output = byteArrayOf()
        private var offset = 0

        fun command(raw: ByteArray): ByteArray {
            if (raw.contentEquals(read)) {
                require(offset < output.size)
                return next()
            }
            require(offset == output.size && raw.size in 7..CHUNK+6)
            require(raw[0] == 0x80.toByte() && raw[1] == 0x20.toByte() && raw[3] == 0.toByte() && raw.last() == 0.toByte())
            val length = raw[4].toInt() and 255
            require(length == raw.size - 6 && input.size() + length <= LIMIT)
            require(raw[2].toInt() in 0..1)
            input.write(raw, 5, length)
            if (raw[2].toInt() == 0) return ok
            output = handle(input.toByteArray())
            require(output.size <= LIMIT)
            input.reset()
            offset = 0
            return next()
        }

        private fun next(): ByteArray {
            val end = minOf(offset + CHUNK, output.size)
            val data = output.copyOfRange(offset, end)
            offset = end
            return data + if (end == output.size) ok else more
        }
    }
}
