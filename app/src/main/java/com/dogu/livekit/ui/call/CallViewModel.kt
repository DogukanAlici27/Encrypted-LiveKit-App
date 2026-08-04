package com.dogu.livekit.ui.call

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.core.hardware.AudioManagerCompat
import com.dogu.livekit.core.logging.Logger
import com.dogu.livekit.core.encryption.EncryptionManager
import com.dogu.livekit.core.encryption.KeyManager
import com.dogu.livekit.core.util.ImageUtils
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.domain.call.CallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.*
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class CallEvent {
    data class Status(val message: String, val duration: Long = 5000) : CallEvent()
    data class Error(val message: String) : CallEvent()
    data class ParticipantJoined(val identity: String) : CallEvent()
    data class ParticipantLeft(val identity: String) : CallEvent()
    data class TrackAdded(val identity: String, val track: VideoTrack, val isMuted: Boolean) : CallEvent()
    data class TrackRemoved(val identity: String) : CallEvent()
    data class TrackMuted(val identity: String, val isMuted: Boolean) : CallEvent()
    data class Connect(val url: String, val token: String, val useVideo: Boolean, val roomKey: String?) : CallEvent()
    data class ChatMessage(val sender: String, val message: String) : CallEvent()
    object CallStarted : CallEvent()
    object CallEnded : CallEvent()
}

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

    private val _events = MutableSharedFlow<CallEvent>()
    val events = _events.asSharedFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline = _isOnline.asStateFlow()

    private val _profilePhoto = MutableStateFlow<Bitmap?>(null)
    val profilePhoto = _profilePhoto.asStateFlow()

    private var heartbeatJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var callTimeoutJob: Job? = null
    
    private var heartbeatFailCount = 0
    private var refreshFailCount = 0

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

    fun switchCamera() {
        viewModelScope.launch {
            val room = CallManager.room ?: return@launch
            val localParticipant = room.localParticipant
            val videoTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
            videoTrack?.let {
                it.switchCamera()
                _events.emit(CallEvent.Status("Kamera değiştirildi"))
            }
        }
    }

    suspend fun connectToRoom(context: Context, url: String, token: String, useVideo: Boolean, roomKey: String?) {
        try {
            Logger.d("Connecting to room: $url")
            val myIdentity = sessionPreferences.getCurrentIdentity() ?: "Me"
            val localIdentity = "$myIdentity (Sen)"

            val roomOptions = io.livekit.android.RoomOptions(
                adaptiveStream = false,
                dynacast = false,
                e2eeOptions = roomKey?.let { EncryptionManager.getE2EEOptions(it) }
            )

            CallManager.currentRoomKey = roomKey
            val newRoom = CallManager.connect(
                context,
                url,
                token,
                useVideo,
                null,
                null,
                roomOptions
            )

            _roomState.value = Room.State.CONNECTED
            _isMicMuted.value = false
            _isSpeakerOn.value = true
            _isCameraOn.value = useVideo

            viewModelScope.launch {
                newRoom.events.collect { event ->
                    handleRoomEvent(event, localIdentity, newRoom)
                }
            }

            // Handle existing participants
            viewModelScope.launch {
                repeat(8) { attempt ->
                    delay(if (attempt == 0) 500.milliseconds else 2000.milliseconds)
                    newRoom.remoteParticipants.values.forEach { participant ->
                        participant.trackPublications.values.forEach { pub ->
                            if (pub is io.livekit.android.room.track.RemoteTrackPublication) {
                                if (!pub.subscribed) pub.setSubscribed(true)
                                (pub.track as? VideoTrack)?.let { track ->
                                    _events.emit(CallEvent.TrackAdded(participant.identity?.value ?: "Unknown", track, pub.muted))
                                }
                            }
                        }
                    }
                }
            }

            // Handle local track
            if (useVideo) {
                viewModelScope.launch {
                    var localTrack: LocalVideoTrack? = null
                    repeat(20) {
                        if (localTrack == null) {
                            localTrack = newRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                            if (localTrack == null) delay(200.milliseconds)
                        }
                    }
                    localTrack?.let {
                        _events.emit(CallEvent.TrackAdded(localIdentity, it, !isCameraOn.value))
                    }
                }
            }
            
            _events.emit(CallEvent.CallStarted)

        } catch (e: Exception) {
            Logger.e("Connection error", e)
            _events.emit(CallEvent.Error("Görüşme başlatılamadı: ${e.message}"))
            leaveRoom(true)
        }
    }

    private suspend fun handleRoomEvent(event: RoomEvent, localIdentity: String, room: Room) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack) {
                    _events.emit(CallEvent.TrackAdded(event.participant.identity?.value ?: "Unknown", track, event.publication.muted))
                }
            }
            is RoomEvent.TrackMuted -> {
                val identity = if (event.participant is io.livekit.android.room.participant.LocalParticipant) localIdentity else event.participant.identity?.value ?: ""
                if (event.publication.kind == Track.Kind.VIDEO) {
                    _events.emit(CallEvent.TrackMuted(identity, true))
                }
            }
            is RoomEvent.TrackUnmuted -> {
                val identity = if (event.participant is io.livekit.android.room.participant.LocalParticipant) localIdentity else event.participant.identity?.value ?: ""
                if (event.publication.kind == Track.Kind.VIDEO) {
                    _events.emit(CallEvent.TrackMuted(identity, false))
                }
            }
            is RoomEvent.ParticipantConnected -> {
                _events.emit(CallEvent.Status("Katılımcı girdi: ${event.participant.identity?.value}"))
            }
            is RoomEvent.TrackUnsubscribed -> {
                _events.emit(CallEvent.TrackRemoved(event.participant.identity?.value ?: ""))
            }
            is RoomEvent.ParticipantDisconnected -> {
                val identity = event.participant.identity?.value ?: ""
                _events.emit(CallEvent.TrackRemoved(identity))
                _events.emit(CallEvent.ParticipantLeft(identity))
            }
            is RoomEvent.Disconnected -> {
                _events.emit(CallEvent.CallEnded)
            }
            is RoomEvent.DataReceived -> {
                val message = String(event.data)
                val participantName = event.participant?.identity?.value ?: "Biri"
                if (message == "REJECTED") {
                    if (room.remoteParticipants.isEmpty()) {
                        callTimeoutJob?.cancel()
                        leaveRoom(true, "$participantName aramayı reddetti.")
                    } else {
                        _events.emit(CallEvent.Status("$participantName aramayı reddetti."))
                    }
                } else if (message.startsWith("LEFT_CALL:")) {
                    if (room.remoteParticipants.size <= 1) {
                        leaveRoom(true, "karşı taraf görüşmeden ayrıldı")
                    } else {
                        _events.emit(CallEvent.Status("${message.substringAfter("LEFT_CALL:")} adlı kullanıcı ayrıldı"))
                    }
                } else if (message.startsWith("CHAT:")) {
                    val chatText = message.substringAfter("CHAT:")
                    _events.emit(CallEvent.ChatMessage(participantName, chatText))
                }
            }
            else -> {}
        }
    }

    fun leaveRoom(shouldNotify: Boolean, reason: String? = null) {
        viewModelScope.launch {
            if (shouldNotify && CallManager.isConnected()) {
                val myIdentity = sessionPreferences.getCurrentIdentity() ?: "Biri"
                CallManager.publishData("LEFT_CALL:$myIdentity")
                delay(300)
            }
            CallManager.disconnect()
            _roomState.value = Room.State.DISCONNECTED
            _events.emit(CallEvent.CallEnded)
            reason?.let { _events.emit(CallEvent.Status(it)) }
        }
    }

    fun startCall(targetIdentity: String, useVideo: Boolean) {
        viewModelScope.launch {
            val myIdentity = sessionPreferences.getCurrentIdentity() ?: return@launch
            
            val roomKey = EncryptionManager.generateRoomKey()
            val encryptedKeysJson = buildEncryptedKeysForTargets(targetIdentity, roomKey)

            val baseRoomName = (listOf(myIdentity) + targetIdentity.split(",").map { it.trim() })
                .sorted()
                .joinToString("_")
            val prefixedRoomName = if (useVideo) "VIDEO_ROOM_$baseRoomName" else "AUDIO_ROOM_$baseRoomName"

            val result = userRepository.fetchToken(myIdentity, targetIdentity, prefixedRoomName, encryptedKeysJson, isVideo = useVideo)
            if (result.isSuccess) {
                val json = result.getOrNull()!!
                userRepository.saveCallLog(targetIdentity, "OUTGOING")
                
                _events.emit(CallEvent.Connect(json.getString("url"), json.getString("token"), useVideo, roomKey))
            } else {
                _events.emit(CallEvent.Error("Arama başarısız"))
            }
        }
    }

    suspend fun buildEncryptedKeysForTargets(targetCsv: String, roomKey: String): String =
        withContext(Dispatchers.IO) {
            val targets = targetCsv.split(",").map { it.trim() }.toSet()
            val obj = JSONObject()
            targets.forEach { uid ->
                userRepository.fetchLocalUser(uid)?.let { user ->
                    user.publicKey?.let { pubKey ->
                        try {
                            val encrypted = KeyManager.encryptForPublicKey(pubKey, roomKey.toByteArray())
                            obj.put(uid, encrypted)
                        } catch (e: Exception) { Logger.e("Encryption error for $uid", e) }
                    }
                }
            }
            obj.toString()
        }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                val identity = sessionPreferences.getCurrentIdentity()
                if (identity != null) {
                    val res = userRepository.sendHeartbeat(identity)
                    if (res.isSuccess) {
                        heartbeatFailCount = 0
                        _isOnline.value = true
                    } else {
                        heartbeatFailCount++
                        _isOnline.value = false
                        if (heartbeatFailCount >= 3) _events.emit(CallEvent.Status("Bağlantı zayıf...", 3000))
                    }
                }
                delay(30.seconds)
            }
        }
    }

    fun triggerImmediateHeartbeat() {
        startHeartbeat()
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
    }

    fun refreshUsers() {
        viewModelScope.launch {
            val res = userRepository.fetchUsers()
            if (res.isSuccess) {
                userRepository.syncUsers(res.getOrThrow())
            }
        }
    }

    fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60.seconds)
                val res = userRepository.fetchUsers()
                if (res.isSuccess) {
                    refreshFailCount = 0
                    userRepository.syncUsers(res.getOrThrow())
                } else {
                    refreshFailCount++
                }
            }
        }
    }

    fun loadOwnProfilePhoto() {
        viewModelScope.launch {
            val identity = sessionPreferences.getCurrentIdentity() ?: return@launch
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val usersArray = result.getOrNull() ?: return@launch
                for (i in 0 until usersArray.length()) {
                    val user = usersArray.getJSONObject(i)
                    if (user.optString("identity") == identity) {
                        val photoBase64 = user.optString("profilePhoto", "")
                        if (photoBase64.isNotEmpty()) {
                            _profilePhoto.value = ImageUtils.base64ToBitmap(photoBase64)
                        }
                        break
                    }
                }
            }
        }
    }

    fun inviteParticipantToCurrentCall(targetIdentity: String) {
        viewModelScope.launch {
            val room = CallManager.room ?: return@launch
            val currentRoomName = room.name ?: return@launch
            val myIdentity = sessionPreferences.getCurrentIdentity() ?: return@launch
            val roomKey = CallManager.currentRoomKey
            
            val encryptedKeysJson = if (roomKey != null) {
                buildEncryptedKeysForTargets(targetIdentity, roomKey)
            } else {
                "{}"
            }

            val result = userRepository.fetchToken(myIdentity, targetIdentity, currentRoomName, encryptedKeysJson, isVideo = isCameraOn.value)
            if (result.isSuccess) {
                _events.emit(CallEvent.Status("$targetIdentity davet edildi, bekleniyor..."))
            } else {
                _events.emit(CallEvent.Error("Davet gönderilemedi."))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        autoRefreshJob?.cancel()
    }
}
