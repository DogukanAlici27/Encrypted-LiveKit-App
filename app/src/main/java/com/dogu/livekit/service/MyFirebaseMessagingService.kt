package com.dogu.livekit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dogu.livekit.domain.call.CallManager
import com.dogu.livekit.ui.call.IncomingCallActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.data.repository.MessageRepository
import com.dogu.livekit.data.local.prefs.SessionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var groupRepository: com.dogu.livekit.data.repository.GroupRepository

    @Inject
    lateinit var sessionPreferences: SessionPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CALL_NOTIFICATION_ID = 1001
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val type = remoteMessage.data["type"]
        if (type == "CHAT_MESSAGE") {
            handleChatMessage(remoteMessage)
            return
        }

        if (type == "GROUP_CHAT_MESSAGE") {
            handleGroupChatMessage(remoteMessage)
            return
        }

        if (type == "READ_RECEIPT") {
            handleReadReceipt(remoteMessage)
            return
        }

        val caller = remoteMessage.data["caller"] ?: "Bilinmeyen"
        val room = remoteMessage.data["room"]
        
        // Sunucu desteği olmadan oda isminden arama türünü çözüyoruz
        val isVideo = if (room != null) {
            !room.startsWith("AUDIO_")
        } else {
            remoteMessage.data["video"]?.toBoolean() ?: true
        }
        
        Log.e("FCM_DEBUG", "Gelen Bildirim Verisi -> Room: $room, isVideo: $isVideo")
        
        // YENİ: sunucunun sadece taşıdığı (asla çözmediği), bu cihaza özel şifreli oda anahtarı
        val encryptedRoomKey = remoteMessage.data["encryptedRoomKey"]

        Log.d("FCM", "Bildirim geldi. Arayan: $caller, Room: $room, Video: $isVideo")

        // 0. ENGEL KONTROLÜ
        serviceScope.launch {
            val user = userRepository.fetchLocalUser(caller)
            if (user != null && user.isBlocked) {
                Log.d("FCM", "Engellenen kullanıcı ($caller) arıyor. Sessizce reddediliyor...")
                if (room != null) {
                    silentDeclineCall(caller, room)
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                processMessage(remoteMessage, caller, room, encryptedRoomKey, isVideo)
            }
        }
    }

    private fun handleChatMessage(remoteMessage: RemoteMessage) {
        val sender = remoteMessage.data["sender"] ?: return
        val recipient = remoteMessage.data["recipient"]
        val content = remoteMessage.data["content"] ?: ""
        val serverMsgId = remoteMessage.data["serverMsgId"]
        val timestampStr = remoteMessage.data["timestamp"]
        val timestamp = timestampStr?.toLongOrNull() ?: System.currentTimeMillis()

        serviceScope.launch {
            // 1. YEREL ENGEL KONTROLÜ
            val user = userRepository.fetchLocalUser(sender)
            if (user != null && user.isBlocked) {
                Log.d("FCM", "Engellenen kullanıcıdan ($sender) gelen mesaj reddedildi.")
                return@launch
            }

            messageRepository.receiveMessage(sender, content, timestamp, recipient = recipient, remoteId = serverMsgId)
            
            if (user?.isMuted == true) {
                Log.d("FCM", "Mesaj sessize alınan kullanıcıdan ($sender) geldi. Bildirim gösterilmiyor.")
                return@launch
            }
            withContext(Dispatchers.Main) {
                showChatNotification(sender, content)
            }
        }
    }

    private fun handleGroupChatMessage(remoteMessage: RemoteMessage) {
        val sender = remoteMessage.data["sender"] ?: return
        val groupId = remoteMessage.data["groupId"] ?: return
        val groupName = remoteMessage.data["groupName"] ?: "Grup"
        val content = remoteMessage.data["content"] ?: ""
        val serverMsgId = remoteMessage.data["serverMsgId"]
        val timestampStr = remoteMessage.data["timestamp"]
        val timestamp = timestampStr?.toLongOrNull() ?: System.currentTimeMillis()

        serviceScope.launch {
            // 1. YEREL ENGEL KONTROLÜ (Grup içinde olsa bile engellenen kişiyi görme)
            val senderUser = userRepository.fetchLocalUser(sender)
            if (senderUser != null && senderUser.isBlocked) {
                Log.d("FCM", "Grup içindeki engelli kullanıcıdan ($sender) gelen mesaj reddedildi.")
                return@launch
            }

            // Önce grubu yerelde kontrol et/oluştur
            val existingGroup = groupRepository.getGroup(groupId)
            
            // 2. SESSİZE ALMA KONTROLÜ
            if (existingGroup?.isMuted == true) {
                Log.d("FCM", "Grup ($groupName) sessize alınmış. Mesaj kaydediliyor ama bildirim gösterilmiyor.")
                messageRepository.receiveMessage(sender, content, timestamp, groupId, remoteId = serverMsgId)
                return@launch
            }

            if (existingGroup == null) {
                val newGroup = com.dogu.livekit.data.local.entity.GroupEntity(
                    id = groupId,
                    name = groupName,
                    owner = "", // Bilmiyoruz, önemli değil
                    members = "" // Bilmiyoruz, önemli değil
                )
                groupRepository.saveGroup(newGroup)
            }

            messageRepository.receiveMessage(sender, content, timestamp, groupId, remoteId = serverMsgId)
            
            withContext(Dispatchers.Main) {
                showGroupChatNotification(groupId, groupName, sender, content)
            }
        }
    }

    // Kanal her iki bildirim türünden önce de garanti edilmeli; grup mesajı, birebir mesajdan
    // önce gelirse kanal hiç oluşmamış olur ve Android 8+ bildirimi sessizce düşürür.
    private fun ensureChatChannel(notificationManager: NotificationManager): String {
        val channelId = "chat_messages_channel_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mesajlar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Yeni mesaj bildirimleri"
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun showGroupChatNotification(groupId: String, groupName: String, sender: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChatChannel(notificationManager)

        val intent = Intent(this, com.dogu.livekit.ui.chat.ChatActivity::class.java).apply {
            putExtra("groupId", groupId)
            putExtra("groupName", groupName)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, groupId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(groupName)
            .setContentText("$sender: $content")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(groupId.hashCode(), notification)
    }

    private fun handleReadReceipt(remoteMessage: RemoteMessage) {
        val reader = remoteMessage.data["reader"] ?: return
        serviceScope.launch {
            messageRepository.receiveReadReceipt(reader)
        }
    }

    private fun showChatNotification(sender: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChatChannel(notificationManager)

        val intent = Intent(this, com.dogu.livekit.ui.chat.ChatActivity::class.java).apply {
            putExtra("recipient", sender)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, sender.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }

    private fun processMessage(remoteMessage: RemoteMessage, caller: String, room: String?, encryptedRoomKey: String?, isVideo: Boolean) {
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
            showCallNotification(caller, room, encryptedRoomKey, isVideo)
        }
    }

    private suspend fun silentDeclineCall(caller: String, room: String) {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        // target null: "room" parametresi yeterli; sahte bir hedef adı gönderirsek sunucu onu
        // gerçek bir arama hedefi sanır (o isimde kullanıcı varsa FCM bile gönderir).
        val result = userRepository.fetchToken(identity, null, room)
        if (result.isSuccess) {
            val json = result.getOrNull()!!
            try {
                val rejectRoom = io.livekit.android.LiveKit.create(applicationContext)
                rejectRoom.connect(json.getString("url"), json.getString("token"))
                
                var retry = 0
                while (rejectRoom.state != io.livekit.android.room.Room.State.CONNECTED && retry < 20) {
                    kotlinx.coroutines.delay(200)
                    retry++
                }

                if (rejectRoom.state == io.livekit.android.room.Room.State.CONNECTED) {
                    rejectRoom.localParticipant.publishData("REJECTED".toByteArray())
                    kotlinx.coroutines.delay(500)
                }
                rejectRoom.disconnect()
                userRepository.saveCallLog(caller, "BLOCKED_CALL")
            } catch (e: Exception) {
                Log.e("FCM", "Silent decline hatası: ${e.message}")
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Yeni Token: $token")
        getSharedPreferences("LiveKit", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    private fun showCallNotification(caller: String, room: String, encryptedRoomKey: String?, isVideo: Boolean) {
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
            putExtra("is_video", isVideo)
            // YENİ: şifreli oda anahtarı, çözülmeden IncomingCallActivity'ye taşınıyor.
            // Çözme işlemi ancak kullanıcı "Kabul Et"e basınca, orada gerçekleşecek.
            putExtra("incoming_room_key", encryptedRoomKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeTitle = if (isVideo) "Gelen Görüntülü Arama" else "Gelen Sesli Arama"

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callTypeTitle)
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