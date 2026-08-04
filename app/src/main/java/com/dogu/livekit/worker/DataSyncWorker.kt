package com.dogu.livekit.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.core.logging.Logger
import com.google.firebase.messaging.FirebaseMessaging
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

@HiltWorker
class DataSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userRepository: UserRepository,
    private val sessionPreferences: SessionPreferences
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Logger.d("🔄 DataSyncWorker başlatıldı")

            // 1. Bekleyen verileri sunucuya it (Push)
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                userRepository.syncUnsyncedUsers(fcmToken)
            } catch (e: Exception) {
                Logger.e("❌ Veriler sunucuya itilemedi", e)
            }

            // 2. Kullanıcı listesini sunucudan çek (Pull)
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val serverUsers = result.getOrNull() ?: JSONArray()
                userRepository.syncUsers(serverUsers)
            } else {
                Logger.d("⚠️ Kullanıcı listesi çekilemedi")
                return Result.retry()
            }

            // 3. Blok listesini senkronize et
            val myIdentity = sessionPreferences.getCurrentIdentity()
            if (myIdentity != null) {
                userRepository.syncBlockedUsersFromServer(myIdentity)
            }

            Logger.d("✅ DataSyncWorker başarıyla tamamlandı")
            Result.success()
        } catch (e: Exception) {
            Logger.e("❌ DataSyncWorker hatası", e)
            Result.retry()
        }
    }
}
