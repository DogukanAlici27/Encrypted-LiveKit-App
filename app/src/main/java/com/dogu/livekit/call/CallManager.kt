package com.dogu.livekit.call

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dogu.livekit.logging.Logger
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object CallManager {
    var room: Room? = null
    // YENİ: aktif görüşmenin AES oda anahtarı (E2EE). "Kullanıcı Ekle" ile birini
    // davet ederken aynı anahtarı yeniden şifreleyip yollamak için burada tutuyoruz.
    var currentRoomKey: String? = null
    private var areRenderersInitialized = false

    suspend fun connect(
        context: Context,
        url: String,
        token: String,
        useVideo: Boolean,
        localRenderer: SurfaceViewRenderer? = null,
        remoteRenderer: SurfaceViewRenderer? = null,
        roomOptions: RoomOptions? = null
    ): Room {
        Logger.d("Connecting to room: $url")

        // Foreground Service başlat
        val serviceIntent = Intent(context, CallService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)

        try {
            // Önceki odadan temiz ayrıl
            room?.disconnect()

            val newRoom = LiveKit.create(context, roomOptions ?: RoomOptions())
            room = newRoom // Bağlanmadan önce atayalım ki isBusy() doğru çalışsın

            // ÖNEMLİ: Renderer ilklendirmesini ana thread'de yapmalıyız
            withContext(Dispatchers.Main) {
                try {
                    localRenderer?.let { newRoom.initVideoRenderer(it) }
                    remoteRenderer?.let { newRoom.initVideoRenderer(it) }
                } catch (e: Exception) {
                    Logger.e("Renderer init hatası: ${e.message}")
                }
            }

            newRoom.connect(url, token)
            newRoom.localParticipant.setMicrophoneEnabled(true)

            if (useVideo) {
                newRoom.localParticipant.setCameraEnabled(true)

                // Yerel track'i yakalamak için daha güvenli bir yöntem: Events üzerinden dinleyelim
                // Ama hızlıca getTrackPublication ile भी kontrol edelim

                withContext(Dispatchers.Main) {
                    var retryCount = 0
                    var localTrack: LocalVideoTrack? = null
                    while (retryCount < 20 && localTrack == null) {
                        localTrack = newRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                        if (localTrack == null) {
                            delay(200)
                            retryCount++
                        }
                    }

                    if (localTrack != null && localRenderer != null) {
                        localRenderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        localRenderer.setMirror(true) // Yerel kamera görüntüsü aynalanır
                        localTrack.addRenderer(localRenderer)
                        localRenderer.visibility = android.view.View.VISIBLE
                        Logger.d("Yerel kamera track'i renderer'a başarıyla eklendi.")
                    } else if (localTrack != null) {
                        Logger.d("Yerel kamera track'i oluşturuldu ama renderer null, manuel eklenebilir.")
                    } else {
                        Logger.e("Yerel kamera track'i oluşturulamadı! Manuel deneme...")
                    }
                }
            }

            return newRoom
        } catch (e: Exception) {
            Logger.e("CallManager.connect hatası", e)
            disconnect(context)
            throw e
        }
    }

    fun disconnect(context: Context? = null) {
        room?.disconnect()
        room = null
        currentRoomKey = null // YENİ: görüşme bitince anahtarı da bellekten temizle

        context?.let {
            val serviceIntent = Intent(it, CallService::class.java)
            it.stopService(serviceIntent)
        }
    }

    suspend fun publishData(data: String) {
        room?.localParticipant?.publishData(data.toByteArray())
    }

    fun isConnected(): Boolean {
        return room?.state == Room.State.CONNECTED
    }

    fun isBusy(): Boolean {
        return room != null && room?.state != Room.State.DISCONNECTED
    }
}
