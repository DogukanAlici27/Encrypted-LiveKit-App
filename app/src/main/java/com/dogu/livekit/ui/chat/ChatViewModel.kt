package com.dogu.livekit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dogu.livekit.data.local.entity.GroupEntity
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.local.entity.UserEntity
import com.dogu.livekit.data.repository.GroupRepository
import com.dogu.livekit.data.repository.MessageRepository
import com.dogu.livekit.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository
) : ViewModel() {

    fun getMessages(user: String): Flow<List<MessageEntity>> {
        return messageRepository.getChatMessages(user)
    }

    fun getGroupMessages(groupId: String): Flow<List<MessageEntity>> {
        return messageRepository.getGroupMessages(groupId)
    }

    fun getUser(identity: String): Flow<UserEntity?> {
        return userRepository.getLocalUserFlow(identity)
    }

    fun getGroup(groupId: String, onResult: (GroupEntity?) -> Unit) {
        viewModelScope.launch {
            onResult(groupRepository.getGroup(groupId))
        }
    }

    fun sendMessage(recipient: String, text: String) {
        viewModelScope.launch {
            messageRepository.sendMessage(recipient, text)
        }
    }

    fun sendGroupMessage(groupId: String, text: String) {
        viewModelScope.launch {
            messageRepository.sendGroupMessage(groupId, text)
        }
    }

    fun createGroup(name: String, members: List<String>, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onResult(groupRepository.createGroup(name, members))
        }
    }

    fun markAsRead(sender: String) {
        viewModelScope.launch {
            messageRepository.markAsRead(sender)
        }
    }

    fun markGroupAsRead(groupId: String) {
        viewModelScope.launch {
            messageRepository.markGroupAsRead(groupId)
        }
    }

    fun getAllGroups(): Flow<List<GroupEntity>> {
        return groupRepository.getAllGroups()
    }
    
    fun getLastMessages(): Flow<List<MessageEntity>> {
        return messageRepository.getLastMessages()
    }

    fun deleteConversation(user: String) {
        viewModelScope.launch {
            messageRepository.deleteConversation(user)
        }
    }

    fun deleteGroupConversation(groupId: String) {
        viewModelScope.launch {
            groupRepository.updateLastMessage(groupId, "", System.currentTimeMillis())
            messageRepository.deleteGroupMessages(groupId)
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

    fun toggleMute(identity: String, currentStatus: Boolean) {
        viewModelScope.launch {
            userRepository.updateMutedStatus(identity, !currentStatus)
        }
    }

    fun toggleGroupMute(groupId: String, currentStatus: Boolean) {
        viewModelScope.launch {
            groupRepository.updateMutedStatus(groupId, !currentStatus)
        }
    }

    fun reportMessageRead(remoteId: String) {
        viewModelScope.launch {
            messageRepository.reportMessageStatus(remoteId, "read")
        }
    }

    fun getMessageStatus(remoteId: String, onResult: (Result<org.json.JSONObject>) -> Unit) {
        viewModelScope.launch {
            onResult(messageRepository.fetchMessageStatus(remoteId))
        }
    }
}
