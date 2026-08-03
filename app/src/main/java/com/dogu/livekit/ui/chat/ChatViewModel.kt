package com.dogu.livekit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.local.entity.UserEntity
import com.dogu.livekit.data.repository.MessageRepository
import com.dogu.livekit.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    fun getMessages(user: String): Flow<List<MessageEntity>> {
        return messageRepository.getChatMessages(user)
    }

    fun getUser(identity: String): Flow<UserEntity?> {
        return userRepository.getLocalUserFlow(identity)
    }

    fun sendMessage(recipient: String, text: String) {
        viewModelScope.launch {
            messageRepository.sendMessage(recipient, text)
        }
    }

    fun markAsRead(sender: String) {
        viewModelScope.launch {
            messageRepository.markAsRead(sender)
        }
    }
    
    fun getLastMessages(): Flow<List<MessageEntity>> {
        return messageRepository.getLastMessages()
    }

    fun deleteConversation(user: String) {
        viewModelScope.launch {
            messageRepository.deleteConversation(user)
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId)
        }
    }

    fun deleteMessageForEveryone(message: MessageEntity) {
        viewModelScope.launch {
            messageRepository.deleteMessageForEveryone(message)
        }
    }
}
