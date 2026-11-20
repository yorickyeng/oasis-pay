package ru.oasispay.data.protocol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import ru.oasispay.BuildConfig
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

internal class DeviceKeyException(message: String) : java.security.GeneralSecurityException(message)

internal class DeviceKey(role: String) {
    private val alias = "oasis-pay-relay-$role-v1"

    init {
        require(role in setOf("terminal", "buyer"))
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun createIfNeeded(): ByteArray {
        if (!store().containsAlias(alias)) {
            try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }
        return publicBytes()
    }

    @Synchronized
    fun publicBytes(): ByteArray {
        val store = store()
        if (!store.containsAlias(alias)) {
            throw DeviceKeyException("Ключ устройства утерян. Сохраните журнал и восстановите регистрацию через оператора банка.")
        }
        requireHardware(store.getKey(alias, null) as PrivateKey)
        return store.getCertificate(alias).publicKey.encoded
    }

    private fun generate(strongBox: Boolean) {
        val parameters = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(parameters)
        }.generateKeyPair()
    }

    @Synchronized
    fun sign(fields: Fields): Cose {
        val public = publicKey(publicBytes())
        val private = store().getKey(alias, null) as PrivateKey
        return Cose.sign(fields, private, public)
    }

    private fun requireHardware(key: PrivateKey) {
        val info = KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (!BuildConfig.DEBUG && info.securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
            info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX) {
            throw DeviceKeyException("На устройстве недоступна аппаратная защита ключа. Для оплаты требуется Android Keystore с TEE или StrongBox.")
        }
    }
}
