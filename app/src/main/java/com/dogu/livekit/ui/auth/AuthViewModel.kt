package com.dogu.livekit.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.data.local.prefs.SessionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val sessionPreferences: SessionPreferences
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState = _authState.asStateFlow()

    private val _event = MutableSharedFlow<AuthEvent>()
    val event = _event.asSharedFlow()

    fun login(identity: String, password: String, fcmToken: String, mode: String, publicKey: String?, isRemembered: Boolean) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = userRepository.auth(mode, identity, password, fcmToken, publicKey)
            
            if (result.isSuccess) {
                userRepository.saveLocalUser(identity, password, publicKey, false)
                sessionPreferences.setLoggedIn(true, identity)
                sessionPreferences.saveRememberMe(identity, password, isRemembered)
                _authState.value = AuthState.Success(identity, isOnline = true)
            } else {
                val exception = result.exceptionOrNull()
                val isNetworkError = exception is java.io.IOException
                val isUserNotFoundError = exception?.message?.contains("404") == true || exception?.message?.contains("401") == true

                if (isNetworkError || isUserNotFoundError) {
                    if (mode == "register") {
                        // For simplicity, checking if user exists locally
                        val existingLocal = userRepository.fetchLocalUser(identity)

                        if (existingLocal != null) {
                            _authState.value = AuthState.Error("Bu isim zaten bu telefonda kayıtlı!")
                            return@launch
                        }

                        userRepository.saveLocalUser(identity, password, publicKey, true)
                        userRepository.scheduleDataSync()
                        sessionPreferences.setLoggedIn(true, identity)
                        sessionPreferences.saveRememberMe(identity, password, isRemembered)
                        _authState.value = AuthState.Success(identity, isOnline = false)
                    } else {
                        val localUser = userRepository.fetchLocalUser(identity)
                        if (localUser != null && localUser.password == password) {
                            sessionPreferences.setLoggedIn(true, identity)
                            sessionPreferences.saveRememberMe(identity, password, isRemembered)
                            _authState.value = AuthState.Success(identity, isOnline = false)
                        } else {
                            _authState.value = AuthState.Error("Giriş başarısız (çevrimdışı)")
                        }
                    }
                } else {
                    _authState.value = AuthState.Error(exception?.message ?: "Bilinmeyen hata")
                }
            }
        }
    }

    fun logout(identity: String) {
        viewModelScope.launch {
            userRepository.sendOffline(identity)
            sessionPreferences.logout()
            _event.emit(AuthEvent.LogoutSuccess)
        }
    }

    fun deleteAccount(identity: String) {
        viewModelScope.launch {
            val result = userRepository.deleteUserOnServer(identity)
            if (result.isSuccess) {
                userRepository.deleteLocalUser(identity)
                sessionPreferences.logout()
                _event.emit(AuthEvent.AccountDeleted)
            } else {
                _event.emit(AuthEvent.Error(result.exceptionOrNull()?.message ?: "Delete failed"))
            }
        }
    }

    fun changePassword(identity: String, oldPass: String, newPass: String) {
        viewModelScope.launch {
            val result = userRepository.changePasswordOnServer(identity, oldPass, newPass)
            if (result.isSuccess) {
                userRepository.updateLocalPassword(identity, newPass, false)
                _event.emit(AuthEvent.PasswordChanged)
            } else {
                _event.emit(AuthEvent.Error(result.exceptionOrNull()?.message ?: "Password change failed"))
            }
        }
    }

    fun updatePhoto(identity: String, base64: String) {
        viewModelScope.launch {
            val result = userRepository.updateUserPhoto(identity, base64)
            if (result.isSuccess) {
                _event.emit(AuthEvent.PhotoUpdated)
            } else {
                _event.emit(AuthEvent.Error("Photo update failed"))
            }
        }
    }

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        data class Success(val identity: String, val isOnline: Boolean) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    sealed class AuthEvent {
        object LogoutSuccess : AuthEvent()
        object AccountDeleted : AuthEvent()
        object PasswordChanged : AuthEvent()
        object PhotoUpdated : AuthEvent()
        data class Error(val message: String) : AuthEvent()
    }
}
