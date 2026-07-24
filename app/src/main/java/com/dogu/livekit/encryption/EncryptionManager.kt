package com.dogu.livekit.encryption

import io.livekit.android.e2ee.E2EEOptions

object EncryptionManager {
    // Şimdilik test parolası burada duruyor, ileride dinamik hale getirilebilir.
    private const val SHARED_ENCRYPTION_KEY = "test-paylasilan-parola-123"

    fun getE2EEOptions(): E2EEOptions {
        return E2EEOptions().apply {
            keyProvider.setSharedKey(SHARED_ENCRYPTION_KEY)
        }
    }
}
