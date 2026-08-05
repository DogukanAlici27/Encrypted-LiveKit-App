package com.dogu.livekit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dogu.livekit.data.local.prefs.SessionPreferences
import kotlinx.coroutines.*

class CallService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "call_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Aktif Görüşme", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LiveKit")
            .setContentText("Görüşme devam ediyor...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 34+: verilmemiş bir izne ait FGS tipiyle başlatmak SecurityException fırlatır
            // (ör. sesli aramada kamera izni hiç verilmemişse). Tipleri izinlere göre kur.
            var serviceTypes = 0
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (serviceTypes == 0) {
                // Mikrofon izni bile yoksa görüşme zaten yapılamaz; tipli FGS başlatma
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(2001, notification, serviceTypes)
        } else {
            startForeground(2001, notification)
        }

        // Heartbeat artık WorkManager ve MainActivity tarafından yönetiliyor
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopForeground(true)
    }
}
