package ru.oasispay.data.protocol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

private const val KEY_ALIAS = "oasis-pay-terminal-v1"

internal object TerminalKey {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun publicKey(): String {
        val store = store()
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
        }
        return b64(this.store().getCertificate(KEY_ALIAS).publicKey.encoded)
    }

    fun sign(value: JSONObject) = Envelope.sign(value, store().getKey(KEY_ALIAS, null) as PrivateKey)
}
