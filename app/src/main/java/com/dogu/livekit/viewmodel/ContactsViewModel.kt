package com.dogu.livekit.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.data.AppDatabase
import com.dogu.livekit.data.entity.UserEntity
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val db: AppDatabase,
    private val sessionPreferences: SessionPreferences
) : ViewModel() {

    val contacts: StateFlow<List<UserEntity>> = db.userDao().getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun refreshContacts() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                userRepository.syncUsers(result.getOrThrow())
            }
            _isRefreshing.value = false
        }
    }

    fun syncUnsynced(fcmToken: String) {
        viewModelScope.launch {
            userRepository.syncUnsyncedUsers(fcmToken)
        }
    }

    /**
     * Engelle / engeli kaldır.
     *
     * Akış:
     * 1. Önce local DB'ye yaz (anında etki — kullanıcı rehberden kaybolur).
     * 2. Ardından sunucuya bildir (arka planda, hata olsa da UI etkilenmez).
     *
     * [identity]   : Engellenen kişinin identity'si
     * [isBlocked]  : true = engelle, false = kaldır
     */
    fun toggleBlockUser(identity: String, isBlocked: Boolean) {
        viewModelScope.launch {
            // 1. Local DB
            userRepository.updateBlockedStatus(identity, isBlocked)

            // 2. Sunucu sync
            val myIdentity = sessionPreferences.getCurrentIdentity() ?: return@launch
            val result = userRepository.sendBlockToServer(myIdentity, identity, isBlocked)
            if (result.isFailure) {
                // Ağ yoksa sessizce log'la; local değişiklik kalıcı olarak DB'de zaten var.
                // Bir sonraki syncBlockedUsersFromServer çağrısında server'a tekrar gönderilecek.
                Log.w("ContactsViewModel", "Blok sunucuya gönderilemedi: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Uygulama açılışında çağrılır.
     * Sunucudaki blok listesiyle local DB'yi senkronize eder.
     *
     * Neden gerekli?
     * - Kullanıcı telefonu değiştirmiş olabilir (local DB sıfır, server dolu).
     * - Uygulama silinip tekrar kurulmuş olabilir.
     * - Ağ yokken yapılan engeller server'a gitmemiş olabilir (local → server push).
     */
    fun syncBlocksFromServer() {
        viewModelScope.launch {
            val myIdentity = sessionPreferences.getCurrentIdentity() ?: return@launch
            val result = userRepository.syncBlockedUsersFromServer(myIdentity)
            if (result.isFailure) {
                Log.w("ContactsViewModel", "Blok listesi sunucudan çekilemedi: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}