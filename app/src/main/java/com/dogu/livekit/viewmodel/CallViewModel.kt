package com.dogu.livekit.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.call.CallManager
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import io.livekit.android.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.Context
import com.dogu.livekit.hardware.AudioManagerCompat

@HiltViewModel
class CallViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val sessionPreferences: SessionPreferences
) : ViewModel() {

    private val _roomState = MutableStateFlow<Room.State>(Room.State.DISCONNECTED)
    val roomState = _roomState.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted = _isMicMuted.asStateFlow()

    private val _isCameraOn = MutableStateFlow(false)
    val isCameraOn = _isCameraOn.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn = _isSpeakerOn.asStateFlow()

    fun toggleMic() {
        viewModelScope.launch {
            val room = CallManager.room ?: return@launch
            val newState = !room.localParticipant.isMicrophoneEnabled()
            room.localParticipant.setMicrophoneEnabled(newState)
            _isMicMuted.value = !newState
        }
    }

    fun toggleCamera() {
        viewModelScope.launch {
            val room = CallManager.room ?: return@launch
            val newState = !room.localParticipant.isCameraEnabled()
            room.localParticipant.setCameraEnabled(newState)
            _isCameraOn.value = newState
        }
    }

    fun setCameraState(on: Boolean) {
        _isCameraOn.value = on
    }

    fun toggleSpeaker(context: Context) {
        val newState = !_isSpeakerOn.value
        AudioManagerCompat.setSpeakerphoneOn(context, newState)
        _isSpeakerOn.value = newState
    }

    fun disconnect() {
        CallManager.disconnect()
        _roomState.value = Room.State.DISCONNECTED
    }
}
