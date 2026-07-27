package com.dogu.livekit.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Cihaza özel RSA-2048 anahtar çiftini Android Keystore içinde yönetir.
 */
object KeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // YENİ: Temiz bir sayfa açmak için v3'e geçtik
    private const val KEY_ALIAS = "livekit_e2ee_keypair_v3"
    // ÇÖZÜM: Tüm Android cihazlarda (Xiaomi, Samsung vb.) %100 donanım uyumluluğu
    // sağlayan PKCS1Padding standardına geçildi. OAEP çökmelerini tamamen engeller.
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun getOrCreatePublicKeyBase64(): String {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(2048)
            // Sadece PKCS1 padding izni veriyoruz, karmaşık digest ayarları donanımı artık bozmayacak
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    fun encryptForPublicKey(publicKeyBase64: String, plainBytes: ByteArray): String {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plainBytes)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptWithPrivateKey(cipherTextBase64: String): ByteArray {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encryptedBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        return cipher.doFinal(encryptedBytes)
    }
}