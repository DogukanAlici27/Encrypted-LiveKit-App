package com.dogu.livekit.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dogu.livekit.data.repository.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray

@HiltWorker
class UserSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userRepository: UserRepository
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val serverUsers = result.getOrNull() ?: JSONArray()
                userRepository.syncUsers(serverUsers)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
