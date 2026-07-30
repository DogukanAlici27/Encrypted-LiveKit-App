package com.dogu.livekit.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dogu.livekit.R
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import com.dogu.livekit.util.ImageUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : AppCompatActivity() {

    @Inject
    lateinit var userRepository: com.dogu.livekit.data.repository.UserRepository

    @Inject
    lateinit var sessionPreferences: SessionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Modern yöntemlerle kilit ekranında göster
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_incoming_call)

        val caller = intent.getStringExtra("incoming_caller") ?: "Bilinmeyen"
        val room = intent.getStringExtra("incoming_room") ?: ""
        // YENİ: FCM push'u ile birlikte gelen, bize özel şifrelenmiş oda anahtarı.
        // Henüz çözülmedi — sadece kabul edersek Keystore'daki özel anahtarımızla çözeceğiz.
        val encryptedRoomKey = intent.getStringExtra("incoming_room_key")

        val callerNames = caller.split(",")
        if (callerNames.size > 1) {
            findViewById<TextView>(R.id.callTypeLabel).text = "GRUP ARAMASI"
            findViewById<TextView>(R.id.callerNameText).text = "${callerNames[0]} ve diğerleri"

            // Diğer katılımcıları daha net gösteren bir metin
            val others = callerNames.joinToString(", ")
            findViewById<TextView>(R.id.callStatusText).text = "Katılımcılar: $others"
        } else {
            findViewById<TextView>(R.id.callTypeLabel).text = "GELEN ARAMA"
            findViewById<TextView>(R.id.callerNameText).text = caller
        }

        // Fotoğrafı sunucudan çekelim
        lifecycleScope.launch {
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val users = result.getOrNull()
                if (users != null) {
                    for (i in 0 until users.length()) {
                        val user = users.getJSONObject(i)
                        if (user.getString("identity") == caller) {
                            val photoBase64 = user.optString("profilePhoto", "")
                            if (photoBase64.isNotEmpty()) {
                                val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                                if (bitmap != null) {
                                    val avatarImg = findViewById<ImageView>(R.id.callerAvatar)
                                    avatarImg.setImageBitmap(bitmap)
                                    avatarImg.setPadding(0, 0, 0, 0)
                                    avatarImg.imageTintList = null
                                }
                            }
                            break
                        }
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.acceptButton).setOnClickListener {
            acceptCall(caller, room, encryptedRoomKey)
        }

        findViewById<MaterialButton>(R.id.declineButton).setOnClickListener {
            declineCall(room)
        }
    }

    private fun acceptCall(caller: String, room: String, encryptedRoomKey: String?) {
        // Bildirimi kapat
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        lifecycleScope.launch {
            // Arama kaydını kaydet
            userRepository.saveCallLog(caller, "INCOMING")

            val identity = sessionPreferences.getCurrentIdentity() ?: "Alıcı"

            // ÖNEMLİ: target'ı KASITLI olarak null gönderiyoruz. "room" parametresi zaten
            // hangi odaya gireceğimizi belirliyor; target'a caller'ı yazarsak sunucu bunu
            // "yeni bir arama başlatılıyor" sanıp arayan kişiye de (kendi aramasını kabul
            // ettiği halde) fazladan bir bildirim gönderiyordu. Bu satır o hatayı çözüyor.
            val result = userRepository.fetchToken(identity, null, room)

            if (result.isSuccess) {
                val json = result.getOrNull()!!

                // --- DEĞİŞEN KISIM BURASI ---
                // FCM'den gelen anahtarın Activity'e ulaşıp ulaşmadığını görelim
                android.util.Log.d("E2EE_TEST", "Gelen encryptedRoomKey değeri: $encryptedRoomKey")

                val decryptedRoomKey: String? = try {
                    if (encryptedRoomKey.isNullOrEmpty()) {
                        android.util.Log.w("E2EE_TEST", "encryptedRoomKey null veya boş geldi, şifre çözülemez!")
                        null
                    } else {
                        String(
                            com.dogu.livekit.encryption.KeyManager.decryptWithPrivateKey(encryptedRoomKey),
                            Charsets.UTF_8
                        )
                    }
                } catch (e: Exception) {
                    // Hatayı sadece mesaj olarak değil, tüm detaylarıyla (stack trace) yazdıralım
                    android.util.Log.e("E2EE_TEST", "Oda anahtarı çözülemedi detaylı hata:", e)
                    null
                }
                // --- DEĞİŞEN KISIM BİTTİ ---

                // MainActivity'ye dön ve bağlan
                val intent = android.content.Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
                    putExtra("url", json.getString("url"))
                    putExtra("token", json.getString("token"))
                    putExtra("start_call", true)
                    // Not: bu, sadece cihaz içi bir Intent - çözülmüş anahtar ağa hiç çıkmıyor.
                    putExtra("room_key", decryptedRoomKey)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this@IncomingCallActivity, "Bağlantı hatası!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    private fun declineCall(room: String) {
        // Bildirimi kapat
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        val caller = intent.getStringExtra("incoming_caller") ?: "Bilinmeyen"

        lifecycleScope.launch {
            // Reddedildi olarak kaydet
            userRepository.saveCallLog(caller, "REJECTED")

            val identity = sessionPreferences.getCurrentIdentity() ?: "Alıcı"

            // Reddetme sinyalini göndermek için odaya bağlanıyoruz
            val result = userRepository.fetchToken(identity, "REJECTER", room)
            if (result.isSuccess) {
                val json = result.getOrNull()!!
                val rejectRoom = io.livekit.android.LiveKit.create(applicationContext)
                try {
                    rejectRoom.connect(json.getString("url"), json.getString("token"))

                    // Bağlantı başarılı olana kadar kısa bir süre bekle (max 5 sn)
                    var retry = 0
                    while (rejectRoom.state != io.livekit.android.room.Room.State.CONNECTED && retry < 25) {
                        kotlinx.coroutines.delay(200)
                        retry++
                    }

                    if (rejectRoom.state == io.livekit.android.room.Room.State.CONNECTED) {
                        rejectRoom.localParticipant.publishData("REJECTED".toByteArray())
                        kotlinx.coroutines.delay(1000) // Verinin sunucuya ulaşması için daha fazla süre tanı
                        com.dogu.livekit.logging.Logger.d("Reddetme sinyali gönderildi.")
                    } else {
                        com.dogu.livekit.logging.Logger.e("Reddetme için odaya bağlanılamadı! State: ${rejectRoom.state}")
                    }
                    rejectRoom.disconnect()
                } catch (e: Exception) {
                    com.dogu.livekit.logging.Logger.e("declineCall hatası: ${e.message}")
                }
            }
            finish()
        }
    }
}