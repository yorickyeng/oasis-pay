package ru.oasispay.data.nfc

internal class NfcSession {
    @Volatile var buyerActive = false
    @Volatile var buyerBusy = false
}
