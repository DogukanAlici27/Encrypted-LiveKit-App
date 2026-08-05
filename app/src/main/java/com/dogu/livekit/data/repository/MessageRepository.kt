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

    fun getGroupMessages(groupId: String): Flow<List<MessageEntity>> {
        return db.messageDao().getGroupMessages(groupId)
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
        val localId = db.messageDao().insertMessage(message)

        // 2. Sunucuya gönder
        try {
            val json = JSONObject().apply {
                put("sender", me)
                put("recipient", recipient)
                put("content", text)
            }
            val request = NetworkClient.createPostRequest("/send-message", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val remoteId = JSONObject(body).optString("serverMsgId")
                    if (remoteId.isNotEmpty()) {
                        db.messageDao().updateRemoteId(localId, remoteId)
                    }
                    Result.success(true)
                }
                else Result.failure(IOException("Server error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendGroupMessage(groupId: String, text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext Result.failure(Exception("Not logged in"))
        
        // 1. Yerel DB'ye kaydet
        val message = MessageEntity(
            sender = me,
            recipient = "", 
            groupId = groupId,
            content = text,
            timestamp = System.currentTimeMillis(),
            isMine = true
        )
        val localId = db.messageDao().insertMessage(message)
        db.groupDao().updateLastMessage(groupId, text, System.currentTimeMillis())

        // 2. Sunucuya gönder
        try {
            val json = JSONObject().apply {
                put("groupId", groupId)
                put("sender", me)
                put("content", text)
            }
            val request = NetworkClient.createPostRequest("/send-group-message", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val remoteId = JSONObject(body).optString("serverMsgId")
                    if (remoteId.isNotEmpty()) {
                        db.messageDao().updateRemoteId(localId, remoteId)
                    }
                    Result.success(true)
                }
                else Result.failure(IOException("Server error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun receiveMessage(sender: String, content: String, timestamp: Long, groupId: String? = null, recipient: String? = null, remoteId: String? = null) = withContext(Dispatchers.IO) {
        val me = recipient ?: sessionPreferences.getCurrentIdentity()
        
        if (me == null && groupId == null) {
            com.dogu.livekit.core.logging.Logger.e("❌ Mesaj kaydedilemedi: recipient (me) bilinmiyor")
            return@withContext
        }

        val message = MessageEntity(
            remoteId = remoteId,
            sender = sender,
            recipient = if (groupId != null) "" else (me ?: ""),
            groupId = groupId,
            content = content,
            timestamp = timestamp,
            isMine = false
        )
        db.messageDao().insertMessage(message)
        if (groupId != null) {
            db.groupDao().updateLastMessage(groupId, content, timestamp)
        }
        
        // İletildi raporu gönder
        if (remoteId != null) {
            reportMessageStatus(remoteId, "delivered")
        }
    }

    suspend fun reportMessageStatus(serverMsgId: String, status: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        try {
            val json = JSONObject().apply {
                put("serverMsgId", serverMsgId)
                put("identity", me)
                put("status", status)
            }
            val request = NetworkClient.createPostRequest("/report-status", json)
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            // Sessizce geç
        }
    }

    suspend fun fetchMessageStatus(serverMsgId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = "${NetworkClient.TOKEN_SERVER_URL}/message-status?serverMsgId=${java.net.URLEncoder.encode(serverMsgId, "UTF-8")}"
            val request = NetworkClient.createGetRequest(url)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(JSONObject(response.body?.string() ?: "{}"))
                else Result.failure(IOException("Error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    suspend fun markGroupAsRead(groupId: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        db.messageDao().markGroupAsRead(groupId, me)
    }

    suspend fun receiveReadReceipt(otherParty: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        db.messageDao().markSentMessagesAsRead(me, otherParty)
    }

    suspend fun deleteConversation(user: String) = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext
        db.messageDao().deleteConversation(me, user)
    }

    suspend fun deleteGroupMessages(groupId: String) = withContext(Dispatchers.IO) {
        db.messageDao().deleteGroupMessages(groupId)
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        db.messageDao().deleteById(messageId)
    }

    suspend fun deleteMessageForEveryone(message: MessageEntity) = withContext(Dispatchers.IO) {
        // Şimdilik sadece yerelden siliyoruz, sunucu tarafında "mesaj geri çekme" altyapısı yok.
        db.messageDao().delete(message)
    }
}
