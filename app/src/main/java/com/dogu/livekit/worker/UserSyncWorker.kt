package com.dogu.livekit.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray

@HiltWorker
class UserSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userRepository: UserRepository,
    private val sessionPreferences: SessionPreferences
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Kullanıcı listesini sunucudan çek ve local DB ile senkronize et
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val serverUsers = result.getOrNull() ?: JSONArray()
                userRepository.syncUsers(serverUsers)
            } else {
                return Result.retry()
            }

            // 2. Blok listesini sunucudan çek ve local DB ile senkronize et
            //    (oturum açık değilse sessizce atla)
            val myIdentity = sessionPreferences.getCurrentIdentity()
            if (myIdentity != null) {
                userRepository.syncBlockedUsersFromServer(myIdentity)
                // Blok sync hatası, user sync'i başarısız saydırmamalı; sadece log'lanır
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}