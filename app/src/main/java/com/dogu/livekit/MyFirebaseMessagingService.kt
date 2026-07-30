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

import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var sessionPreferences: SessionPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CALL_NOTIFICATION_ID = 1001
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val caller = remoteMessage.data["caller"] ?: "Bilinmeyen"
        val room = remoteMessage.data["room"]
        // YENİ: sunucunun sadece taşıdığı (asla çözmediği), bu cihaza özel şifreli oda anahtarı
        val encryptedRoomKey = remoteMessage.data["encryptedRoomKey"]

        Log.d("FCM", "Bildirim geldi. Arayan: $caller, Room: $room")

        // 1. MEŞGULİYET KONTROLÜ
        // YENİ: Artık tamamen susmuyoruz. Zaten görüşmedeysek tam ekran arama ekranını
        // açmak yerine, arayanı bildiren sade bir bildirim gösteriyoruz.
        if (CallManager.isBusy()) {
            Log.d("FCM", "Meşgulüz, tam ekran yerine basit 'kaçan arama' bildirimi gösterilecek.")
            serviceScope.launch {
                userRepository.saveCallLog(caller, "MISSED")
            }
            showMissedCallNotification(caller)
            return
        }

        // 2. KENDİ ARAMAMIZI GÖRMEMEK İÇİN GÜÇLENDİRİLMİŞ KONTROL
        val currentIdentity = sessionPreferences.getCurrentIdentity()?.trim() ?: ""
        val rememberedIdentity = sessionPreferences.getRememberedIdentity()?.trim() ?: ""

        Log.d("FCM", "Kimlik Kontrolü - Current: '$currentIdentity', Remembered: '$rememberedIdentity', Incoming Caller: '$caller'")

        val isSelfCall = caller.isNotEmpty() &&
                (caller.equals(currentIdentity, ignoreCase = true) ||
                        caller.equals(rememberedIdentity, ignoreCase = true))

        if (isSelfCall) {
            Log.d("FCM", "Self-call tespit edildi, bildirim GÖSTERİLMİYOR.")
            return
        }

        if (room != null) {
            showCallNotification(caller, room, encryptedRoomKey)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Yeni Token: $token")
        getSharedPreferences("LiveKit", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    private fun showCallNotification(caller: String, room: String, encryptedRoomKey: String?) {
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
            // YENİ: şifreli oda anahtarı, çözülmeden IncomingCallActivity'ye taşınıyor.
            // Çözme işlemi ancak kullanıcı "Kabul Et"e basınca, orada gerçekleşecek.
            putExtra("incoming_room_key", encryptedRoomKey)
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

    // YENİ: Zaten bir görüşmedeyken gelen aramalar için tam ekran DEĞİL,
    // sade, kısa bir bilgilendirme bildirimi. IncomingCallActivity'yi asla açmıyor,
    // çünkü aktif görüşmeyi kesmek istemiyoruz.
    private fun showMissedCallNotification(caller: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "missed_call_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Kaçan Aramalar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Başka bir görüşmedeyken gelen aramalar için bildirim"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Kaçan Arama")
            .setContentText("$caller seni aradı (şu an başka bir görüşmedesin)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()

        // Her arayan için ayrı bildirim ID'si (caller.hashCode()) kullanıyoruz ki
        // farklı kişiler ararsa bildirimler üst üste binmesin, ayrı ayrı görünsün.
        notificationManager.notify(caller.hashCode(), notification)
    }
}