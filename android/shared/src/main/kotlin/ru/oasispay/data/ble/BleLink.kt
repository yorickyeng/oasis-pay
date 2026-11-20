package ru.oasispay.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.*
import ru.oasispay.data.nfc.*
import ru.oasispay.data.protocol.Cbor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** LE credit-based channels carry the same signed invoice and encrypted messages as NFC. */
@SuppressLint("MissingPermission") // The foreground activity obtains the role's runtime permissions.
internal object BleLink {
    private val service = ParcelUuid.fromString("e1799454-59c2-4760-a402-d8b3d9205c67")
    private const val VERSION: Byte = 1

    private class Wire(socket: BluetoothSocket) {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)
        fun receive(): ByteArray {
            val length = input.readUnsignedShort()
            require(length <= Cbor.LIMIT)
            return ByteArray(length).also(input::readFully)
        }
        fun send(data: ByteArray) {
            require(data.size <= Cbor.LIMIT)
            output.writeShort(data.size)
            output.write(data)
            output.flush()
        }
    }

    private data class Peer(val device: BluetoothDevice, val port: Int)
    private suspend fun nearby(adapter: BluetoothAdapter): Peer {
        val scanner = adapter.bluetoothLeScanner ?: throw RelayException("Включите Bluetooth")
        val found = CompletableDeferred<Peer>()
        val gates = mutableMapOf<String, NearbyGate>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(service) ?: return
                if (data.size != 3 || data[0] != VERSION) return
                val port = (data[1].toInt() and 255) * 256 + (data[2].toInt() and 255)
                if (port !in 0x80..0xff) return
                val stamp = result.timestampNanos / 1_000_000
                if (SystemClock.elapsedRealtime() - stamp !in 0..500) return
                val gate = gates.getOrPut(result.device.address) { NearbyGate() }
                if (gate.observe(result.rssi, stamp)) found.complete(Peer(result.device, port))
            }
            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(RelayException("Не удалось начать поиск BLE ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceData(service, byteArrayOf(VERSION)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try {
            return withTimeoutOrNull(20_000) { found.await() }
                ?: throw RelayException("Сильного сигнала кассы нет. Приложите устройства друг к другу и повторите.")
        } finally { scanner.stopScan(callback) }
    }

    suspend fun exchange(adapter: BluetoothAdapter, block: ((ByteArray) -> ByteArray) -> Unit) = coroutineScope {
        val peer = nearby(adapter)
        val socket = peer.device.createInsecureL2capChannel(peer.port)
        try {
            val job = async(Dispatchers.IO) {
                socket.connect()
                val wire = Wire(socket)
                block { data -> wire.send(data); wire.receive() }
            }
            withTimeoutOrNull(8000) { job.await() }
                ?: throw RelayException("Касса не ответила. Приложите устройства ещё раз.")
        } finally { socket.close() }
    }

    suspend fun serve(adapter: BluetoothAdapter, terminal: ArmedTerminal) = coroutineScope {
        val advertiser = requireNotNull(adapter.bluetoothLeAdvertiser) { "Касса не поддерживает BLE-рекламу" }
        val listener = adapter.listenUsingInsecureL2capChannel()
        val started = CompletableDeferred<Unit>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { started.complete(Unit) }
            override fun onStartFailure(errorCode: Int) { started.completeExceptionally(IllegalStateException("Ошибка рекламы BLE ($errorCode)")) }
        }
        val settings = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW).build()
        val port = listener.psm
        val data = AdvertiseData.Builder().addServiceData(service,
            byteArrayOf(VERSION, (port ushr 8).toByte(), port.toByte())).build()
        try {
            advertiser.startAdvertising(settings, data, callback)
            withTimeoutOrNull(5000) { started.await() } ?: error("Bluetooth не включил рекламу кассы")
            while (isActive) {
                val accept = async(Dispatchers.IO) { listener.accept() }
                val socket = try { accept.await() } finally { if (!isActive) listener.close() }
                try {
                    val work = async(Dispatchers.IO) {
                        CardSession(terminal.repository, "ble-l2cap/1").use { session ->
                            val wire = Wire(socket)
                            try {
                                while (!session.finished) wire.send(session.process(wire.receive()))
                            } catch (_: IOException) { /* A partial exchange can resume on the next tap. */ }
                            catch (_: Exception) { return@async false }
                            session.finished
                        }
                    }
                    val finished = withTimeoutOrNull(8000) { work.await() } == true
                    if (finished) {
                        terminal.update(TerminalUpdate("Подтверждение передано по BLE", stop = true, peerConnected = false))
                        break
                    }
                } finally { socket.close() }
            }
        } finally {
            listener.close()
            advertiser.stopAdvertising(callback)
        }
    }
}
