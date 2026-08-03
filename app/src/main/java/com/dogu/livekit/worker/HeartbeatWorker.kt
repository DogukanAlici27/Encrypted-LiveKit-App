package com.dogu.livekit.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.core.logging.Logger
import com.dogu.livekit.data.local.prefs.SessionPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userRepository: UserRepository,
    private val sessionPreferences: SessionPreferences
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val identity = sessionPreferences.getCurrentIdentity()
            
            if (identity != null) {
                val result = userRepository.sendHeartbeat(identity)
                
                if (result.isSuccess) {
                    Logger.d("✅ Heartbeat başarılı: $identity")
                    Result.success()
                } else {
                    Logger.d("⚠️ Heartbeat fail, retry: $identity")
                    Result.retry() 
                }
            } else {
                Result.success() 
            }
        } catch (e: Exception) {
            Logger.e("❌ Heartbeat error", e)
            Result.retry()
        }
    }
}
