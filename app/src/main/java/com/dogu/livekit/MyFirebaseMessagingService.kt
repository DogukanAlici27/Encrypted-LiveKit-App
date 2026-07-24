package com.dogu.livekit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dogu.livekit.call.CallManager
import com.dogu.livekit.ui.IncomingCallActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CALL_NOTIFICATION_ID = 1001
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val caller = remoteMessage.data["caller"] ?: "Bilinmeyen"
        val room = remoteMessage.data["room"]
        
        Log.d("FCM", "Bildirim geldi. Arayan: $caller, Room: $room")

        // 1. MEŞGULİYET KONTROLÜ
        if (CallManager.isBusy()) {
            Log.d("FCM", "Meşguliyet nedeniyle bildirim engellendi (CallManager.isBusy = true)")
            return
        }

        // 2. KENDİ ARAMAMIZI GÖRMEMEK İÇİN GÜÇLENDİRİLMİŞ KONTROL
        val prefs = getSharedPreferences("LiveKit", MODE_PRIVATE)
        val currentIdentity = prefs.getString("current_identity", "")?.trim() ?: ""
        val rememberedIdentity = prefs.getString("remembered_identity", "")?.trim() ?: ""
        
        Log.d("FCM", "Kimlik Kontrolü - Current: '$currentIdentity', Remembered: '$rememberedIdentity', Incoming Caller: '$caller'")
        
        val isSelfCall = caller.isNotEmpty() && 
                        (caller.equals(currentIdentity, ignoreCase = true) || 
                         caller.equals(rememberedIdentity, ignoreCase = true))

        if (isSelfCall) {
            Log.d("FCM", "Self-call tespit edildi, bildirim GÖSTERİLMİYOR.")
            return
        }
        
        if (room != null) {
            showCallNotification(caller, room)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Yeni Token: $token")
        getSharedPreferences("LiveKit", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    private fun showCallNotification(caller: String, room: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "call_channel_v3" // Ayarların sıfırlanması için v3 yaptık

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Gelen Aramalar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Gelen aramalar için tam ekran uyarı"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
                setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("incoming_room", room)
            putExtra("incoming_caller", caller)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Gelen Görüntülü Arama")
            .setContentText("$caller seni arıyor...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true) 
            .setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI)
            .setVibrate(longArrayOf(0, 500, 500, 500))
            .setFullScreenIntent(fullScreenPendingIntent, true) 
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        notificationManager.notify(CALL_NOTIFICATION_ID, notificationBuilder.build())
        
        try {
            startActivity(fullScreenIntent)
        } catch (e: Exception) {
            Log.e("FCM", "Activity başlatılamadı: ${e.message}")
        }
    }
}
