package com.dogu.livekit

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import io.livekit.android.LiveKit
import androidx.work.*
import com.dogu.livekit.worker.HeartbeatWorker
import com.dogu.livekit.worker.UserSyncWorker
import java.util.concurrent.TimeUnit
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LiveKitApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        LiveKit.init(this)
        scheduleBackgroundWorkers()
    }

    private fun scheduleBackgroundWorkers() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<UserSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork("livekit_heartbeat", ExistingPeriodicWorkPolicy.KEEP, heartbeatRequest)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("user_sync", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
