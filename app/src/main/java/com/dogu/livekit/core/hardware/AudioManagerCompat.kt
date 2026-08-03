package com.dogu.livekit.core.hardware

import android.content.Context
import android.media.AudioManager

object AudioManagerCompat {
    fun setSpeakerphoneOn(context: Context, on: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = on
        if (on) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }
}
