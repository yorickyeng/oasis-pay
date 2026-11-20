package ru.oasispay.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import ru.oasispay.data.protocol.b64
import ru.oasispay.data.protocol.DeviceKeyException
import ru.oasispay.data.protocol.unb64
import ru.oasispay.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

internal object SecretStore {
    private const val LEGACY_ALIAS = "oasis-pay-relay-token-v1"
    private const val ALIAS = "oasis-pay-relay-token-v2"
    private const val PREFIX = "v2."
    private val legacyContext = "offline-relay/1:buyer-access-token".toByteArray()
    private val context = "offline-relay/1:buyer-access-token:v2".toByteArray()

    @Synchronized
    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        val key = existing ?: run {
            if (!create || alias != ALIAS) {
                throw DeviceKeyException("Ключ доступа утерян. Требуется восстановление регистрации в банке.")
            }
            try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (!BuildConfig.DEBUG && info.securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
            info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX) {
            throw DeviceKeyException("На устройстве недоступна аппаратная защита ключа доступа к банку.")
        }
        return key
    }

    private fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).apply {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build())
    }.generateKey()

    fun isLegacy(sealed: String) = !sealed.startsWith(PREFIX)

    fun seal(token: String): String {
        require(token.matches(Regex("[0-9a-f]{64}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ALIAS, create = true))
        cipher.updateAAD(context)
        check(cipher.iv.size == 12)
        return PREFIX + (cipher.iv + cipher.doFinal(token.toByteArray())).b64()
    }

    fun open(sealed: String): String {
        val legacy = isLegacy(sealed)
        val encoded = if (legacy) sealed else sealed.removePrefix(PREFIX)
        require(encoded.length == 123)
        val bytes = encoded.unb64()
        require(bytes.size == 92)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(if (legacy) LEGACY_ALIAS else ALIAS, create = false),
            GCMParameterSpec(128, bytes.copyOf(12)))
        cipher.updateAAD(if (legacy) legacyContext else context)
        val plaintext = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try {
            plaintext.decodeToString().also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        } finally {
            plaintext.fill(0)
        }
    }
}
