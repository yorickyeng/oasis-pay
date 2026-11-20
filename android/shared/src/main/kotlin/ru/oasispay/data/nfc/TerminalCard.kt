package ru.oasispay.data.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.protocol.*

internal data class TerminalUpdate(val message: String, val stop: Boolean = false, val error: Boolean = false, val peerConnected: Boolean? = null)

internal class ArmedTerminal(val repository: TerminalRepository, val update: (TerminalUpdate) -> Unit)

internal class CardSession(private val repo: TerminalRepository, private val transport: String = "nfc-isodep/1") : AutoCloseable {
    private var step = 0
    val finished get() = step == 4
    private var id = 0
    private lateinit var hash: ByteArray
    private lateinit var invoice: ByteArray
    private lateinit var handshake: SecureHandshake.Terminal
    private var channel: SecureChannel? = null
    val endpoint = Apdu.Endpoint(::process)

    fun process(input: ByteArray): ByteArray {
        val reply = when (step) {
            0 -> {
                require(input.size == 4)
                id = ByteBuffer.wrap(input).int
                val request = repo.exchangeRequest()
                hash = request.first
                invoice = request.second
                handshake = SecureHandshake.Terminal(repo.secureIdentity(), id, hash, transport)
                handshake.hello
            }
            1 -> handshake.complete(input).let {
                channel = it
                it.seal(3, invoice)
            }
            2 -> requireNotNull(channel).let {
                it.seal(3, repo.acceptResult(hash, it.open(2, input), handshake.peerDeviceId))
            }
            3 -> {
                repo.markHanded(requireNotNull(channel).open(4, input), id)
                byteArrayOf()
            }
            else -> error("Сеанс завершён")
        }
        step++
        return reply
    }

    override fun close() { channel?.close() }
}

class TerminalCard : HostApduService() {
    companion object {
        @Volatile internal var armed: ArmedTerminal? = null
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var session: CardSession? = null // accessed only on worker
    private var owner: ArmedTerminal? = null
    @Volatile private var generation = 0

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        val terminal = armed ?: return byteArrayOf(0x69, 0x85.toByte())
        val selected = commandApdu.contentEquals(Apdu.select) ||
            commandApdu.contentEquals(Apdu.select.copyOf(Apdu.select.size - 1))
        if (selected) generation++
        val current = generation
        worker.execute {
            if (generation != current || terminal !== armed) return@execute
            val response = try {
                if (selected) {
                    session?.close()
                    session = CardSession(terminal.repository)
                    owner = terminal
                    main.post { if (terminal === armed && generation == current) terminal.update(TerminalUpdate("Телефон приложен", peerConnected = true)) }
                    Apdu.ok
                } else {
                    check(owner === terminal)
                    requireNotNull(session).endpoint.command(commandApdu)
                }
            } catch (_: Exception) {
                session?.close()
                session = null
                byteArrayOf(0x69, 0x85.toByte())
            }
            val finished = session?.finished == true
            main.post {
                if (current == generation && terminal === armed) {
                    sendResponseApdu(response)
                    if (finished) terminal.update(TerminalUpdate("Подписанное подтверждение передано", stop = true, peerConnected = false))
                } else if (current == generation) {
                    sendResponseApdu(byteArrayOf(0x69, 0x85.toByte()))
                }
            }
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        generation++
        armed?.update?.invoke(TerminalUpdate("Для обмена приложите телефон к кассе", peerConnected = false))
        worker.execute { session?.close(); session = null; owner = null }
    }

    override fun onDestroy() {
        generation++
        worker.execute { session?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
