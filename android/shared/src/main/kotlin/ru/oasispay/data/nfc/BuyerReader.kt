package ru.oasispay.data.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.protocol.*

internal class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class BuyerReader(private val buyer: BuyerRepository) {
    @Volatile var useBle = false
    private var bleJob: Job? = null
    private var pendingTag: CompletableDeferred<IsoDep>? = null
    var activity: Activity? = null // main thread
        set(value) {
            if (value == null) bleJob?.cancel()
            if (value == null) pendingTag?.completeExceptionally(RelayException("Откройте кошелёк и повторите касание"))
            field = value
        }

    suspend fun exchange(onEvent: (String) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val screen = activity ?: throw RelayException("Откройте кошелёк для обмена по NFC")
        if (useBle) {
            val bluetooth = screen.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                ?: throw RelayException("Устройство не поддерживает Bluetooth")
            try {
                bleJob = currentCoroutineContext().job
                ru.oasispay.data.ble.BleLink.exchange(bluetooth) { send -> exchangeMessages("ble-l2cap/1", onEvent, send) }
            } catch (error: CancellationException) { throw error }
            catch (error: RelayException) { throw error }
            catch (error: Exception) { throw RelayException("Обмен BLE прерван. Повторите прикладывание к кассе.", error) }
            finally { bleJob = null }
            return@withContext
        }
        val adapter = NfcAdapter.getDefaultAdapter(screen)
        if (adapter?.isEnabled != true) throw RelayException("Включите NFC")
        val discovered = CompletableDeferred<IsoDep>()
        pendingTag = discovered
        try {
            adapter.enableReaderMode(screen, { tag -> IsoDep.get(tag)?.let { discovered.complete(it) } },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            val card = withTimeoutOrNull(20_000) { discovered.await() }
                ?: throw RelayException("Приложите телефон ещё раз к разблокированной кассе с открытым счётом")
            withContext(Dispatchers.IO) {
                card.use {
                    card.connect()
                    card.timeout = 5000
                    require(card.transceive(Apdu.select).contentEquals(Apdu.ok))
                    exchangeMessages("nfc-isodep/1", onEvent) { Apdu.exchange(it, card::transceive) }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: RelayException) { throw error }
        catch (error: Exception) { throw RelayException("NFC-обмен прерван. Повторно приложите телефон к той же кассе.", error) }
        finally {
            if (pendingTag === discovered) pendingTag = null
            adapter.disableReaderMode(screen)
        }
    }
    private fun exchangeMessages(transport: String, onEvent: (String) -> Unit, send: (ByteArray) -> ByteArray) {
        val session = randomBytes(4)
        val id = ByteBuffer.wrap(session).int
        val handshake = SecureHandshake.buyer(buyer.secureIdentity(), send(session), id, transport)
        handshake.channel.use { secure ->
            val bundle = secure.open(3, send(handshake.hello))
            val invoice = Cose.parse(Cbor.decode(bundle).fields().bytes("request"))
            require(invoice.hash.contentEquals(handshake.orderHash) && invoice.fields.text("terminal_id") == handshake.terminalId)
            val result = buyer.receiveBundle(bundle)
            onEvent("Счёт и сертификат кассы проверены")
            if (result != null) {
                val ack = secure.open(3, send(secure.seal(2, result)))
                val handoff = buyer.receiveAcknowledgement(ack, id)
                require(send(secure.seal(4, handoff)).isEmpty())
                onEvent("Касса подтвердила получение результата")
            }
        }
    }
}
