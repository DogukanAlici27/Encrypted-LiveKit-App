package com.dogu.livekit.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.data.AppDatabase
import com.dogu.livekit.data.entity.UserEntity
import com.dogu.livekit.data.repository.UserRepository
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
    private val db: AppDatabase
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
}
