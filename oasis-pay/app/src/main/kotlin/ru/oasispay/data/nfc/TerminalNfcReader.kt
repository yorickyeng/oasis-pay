package ru.oasispay.data.nfc

import android.app.Activity
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import ru.oasispay.data.TerminalRepository
import java.util.concurrent.atomic.AtomicBoolean

internal class TerminalNfcReader(
    private val activity: Activity,
    private val terminal: TerminalRepository,
    private val session: NfcSession,
    private val onExchange: (Result<Boolean>) -> Unit,
) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val reading = AtomicBoolean(false)
    @Volatile private var enabled = false

    val available get() = adapter?.isEnabled == true

    fun configure(buyerActive: Boolean, terminalArmed: Boolean) {
        session.buyerActive = buyerActive
        val nfc = adapter ?: return
        runCatching {
            val emulation = CardEmulation.getInstance(nfc)
            if (buyerActive && nfc.isEnabled) {
                emulation.setPreferredService(activity, ComponentName(activity, BuyerCardService::class.java))
            } else {
                emulation.unsetPreferredService(activity)
            }
        }
        val shouldRead = terminalArmed && nfc.isEnabled
        if (enabled == shouldRead) return
        enabled = shouldRead
        if (shouldRead) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            nfc.enableReaderMode(activity, ::read, flags, null)
        } else {
            nfc.disableReaderMode(activity)
        }
    }

    private fun read(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        if (!enabled || !reading.compareAndSet(false, true)) return
        val outcome = try {
            runCatching {
                val order = terminal.currentOrder()
                check(order.isNotEmpty())
                val delivered = iso.use {
                    it.connect()
                    it.timeout = 2500
                    Apdu.exchange(order, it::transceive) { result -> terminal.acceptResult(order, result) }
                }
                if (delivered) terminal.markDelivered(order)
                delivered
            }
        } finally {
            reading.set(false)
        }
        activity.runOnUiThread { onExchange(outcome) }
    }
}
