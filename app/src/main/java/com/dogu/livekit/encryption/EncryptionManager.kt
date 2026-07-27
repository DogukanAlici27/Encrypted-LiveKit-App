package com.dogu.livekit.encryption


import android.util.Base64
import io.livekit.android.e2ee.E2EEOptions
import java.security.SecureRandom

object EncryptionManager {

    /**
     * Her görüşme başlarken ÇAĞRILAN kişi (arayan) tarafından üretilir.
     * 256 bit'lik (AES-256), tahmin edilemez, rastgele bir oda anahtarı döner.
     *
     * ESKİDEN: sabit, herkeste aynı "test-paylasilan-parola-123" kullanılıyordu.
     * ARTIK: her odanın kendine has, bir kere kullanımlık anahtarı var — bu anahtar
     * asla düz metin olarak ağdan geçmiyor, sadece hedef kişinin RSA genel anahtarıyla
     * şifrelenmiş haliyle taşınıyor (bkz. KeyManager.kt, MainActivity.buildEncryptedKeysForTargets).
     */
    fun generateRoomKey(): String {
        val keyBytes = ByteArray(32) // 32 byte = 256 bit
        SecureRandom().nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }

    /** Verilen oda anahtarıyla LiveKit'in E2EE seçeneklerini oluşturur. */
    fun getE2EEOptions(roomKeyBase64: String): E2EEOptions {
        return E2EEOptions().apply {
            keyProvider.setSharedKey(roomKeyBase64)
        }
    }
}