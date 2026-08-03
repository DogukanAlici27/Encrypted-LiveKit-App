package com.dogu.livekit.data.repository

import com.dogu.livekit.data.local.AppDatabase
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.data.remote.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val sessionPreferences: SessionPreferences
) {
    fun getChatMessages(user: String): Flow<List<MessageEntity>> {
        val me = sessionPreferences.getCurrentIdentity() ?: ""
        return db.messageDao().getChatMessages(me, user)
    }

    suspend fun sendMessage(recipient: String, text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext Result.failure(Exception("Not logged in"))
        
        // 1. Yerel DB'ye kaydet
        val message = MessageEntity(
            sender = me,
            recipient = recipient,
            content = text,
            timestamp = System.currentTimeMillis(),
            isMine = true
        )
        db.messageDao().insertMessage(message)

        // 2. Sunucuya gönder
        try {
            val json = JSONObject().apply {
                put("sender", me)
                put("recipient", recipient)
                put("content", text)
            }
            val request = NetworkClient.createPostRequest("/send-message", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException("Server error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun receiveMessage(sender: String, content: String, timestamp: Long) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        val message = MessageEntity(
            sender = sender,
            recipient = me,
            content = content,
            timestamp = timestamp,
            isMine = false
        )
        db.messageDao().insertMessage(message)
    }

    fun getLastMessages(): Flow<List<MessageEntity>> {
        val me = sessionPreferences.getCurrentIdentity() ?: ""
        return db.messageDao().getLastMessages(me)
    }

    suspend fun markAsRead(sender: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        // 1. Local güncelle
        db.messageDao().markAsRead(sender, me)

        // 2. Server'a bildir
        try {
            val json = JSONObject().apply {
                put("me", me)
                put("sender", sender)
            }
            val request = NetworkClient.createPostRequest("/mark-read", json)
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            // Sessizce geç
        }
    }

    suspend fun receiveReadReceipt(otherParty: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        db.messageDao().markSentMessagesAsRead(me, otherParty)
    }

    suspend fun deleteConversation(user: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        db.messageDao().deleteConversation(me, user)
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        db.messageDao().deleteById(messageId)
    }

    suspend fun deleteMessageForEveryone(message: MessageEntity) = withContext(Dispatchers.IO) {
        // Şimdilik sadece yerelden siliyoruz, sunucu tarafında "mesaj geri çekme" altyapısı yok.
        db.messageDao().delete(message)
    }
}
