package ru.oasispay.data.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import ru.oasispay.OasisPayApplication

class BuyerCardService : HostApduService() {
    private val app get() = application as OasisPayApplication
    private val buyer get() = app.buyer
    private val session get() = app.nfcSession
    private var selected = false
    private var mode = 0
    private var upload = Upload()
    private var request = ""
    private var result = byteArrayOf()

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (!session.buyerActive || !buyer.isConfigured()) return Apdu.unavailable
        return try {
            if (Apdu.isSelect(commandApdu)) {
                onDeactivated(0)
                selected = true
                return Apdu.ok
            }
            require(selected && commandApdu.size >= 5 && commandApdu[0] == 0x80.toByte())
            val instruction = commandApdu[1].toInt() and 255
            val offset = ((commandApdu[2].toInt() and 255) shl 8) or (commandApdu[3].toInt() and 255)
            val length = commandApdu[4].toInt() and 255
            require(commandApdu.size == 5 + length && length <= Apdu.CHUNK)
            require(instruction in listOf(Apdu.APPEND, Apdu.READ_RESULT) || offset == 0)
            require(instruction == Apdu.APPEND || length == 0)
            when (instruction) {
                Apdu.BEGIN_REQUEST, Apdu.BEGIN_ACK -> {
                    if (instruction == Apdu.BEGIN_ACK) require(result.isNotEmpty())
                    mode = instruction
                    upload = Upload()
                }
                Apdu.APPEND -> {
                    require(mode != 0)
                    upload.append(offset, commandApdu.copyOfRange(5, commandApdu.size))
                }
                Apdu.END_REQUEST -> {
                    require(mode == Apdu.BEGIN_REQUEST && !session.buyerBusy)
                    val incoming = upload.read()
                    result = buyer.receiveRequest(incoming).encodeToByteArray()
                    request = incoming
                    mode = 0
                }
                Apdu.READ_RESULT -> {
                    require(request.isNotEmpty() && offset <= result.size)
                    return result.copyOfRange(offset, minOf(result.size, offset + Apdu.CHUNK)) + Apdu.ok
                }
                Apdu.END_ACK -> {
                    require(mode == Apdu.BEGIN_ACK && !session.buyerBusy)
                    buyer.receiveAcknowledgement(request, result.decodeToString(), upload.read())
                    mode = 0
                }
                else -> return Apdu.invalid
            }
            Apdu.ok
        } catch (_: Exception) {
            Apdu.invalid
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = false
        mode = 0
        request = ""
        result = byteArrayOf()
    }
}
