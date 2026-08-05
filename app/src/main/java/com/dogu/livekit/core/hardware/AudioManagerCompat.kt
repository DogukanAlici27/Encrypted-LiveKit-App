package com.dogu.livekit.core.hardware

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object AudioManagerCompat {
    fun setSpeakerphoneOn(context: Context, on: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Görüşme sırasında mod hep IN_COMMUNICATION kalmalı; hoparlörü kapatmak
        // aramadan çıkmak anlamına gelmez (eski kod MODE_NORMAL'a düşürüyordu).
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: isSpeakerphoneOn iletişim modunda çoğu cihazda etkisizdir
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }
}
