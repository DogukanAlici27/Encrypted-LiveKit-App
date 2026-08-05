package com.dogu.livekit.data.repository

import com.dogu.livekit.data.local.AppDatabase
import com.dogu.livekit.data.local.entity.GroupEntity
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.data.repository.MessageRepository
import com.dogu.livekit.data.remote.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val sessionPreferences: SessionPreferences,
    private val messageRepository: MessageRepository
) {
    fun getAllGroups(): Flow<List<GroupEntity>> = db.groupDao().getAllGroups()

    suspend fun createGroup(name: String, members: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext Result.failure(Exception("Not logged in"))
        
        val allMembers = members.toMutableList()
        if (!allMembers.contains(me)) allMembers.add(me)

        try {
            val json = JSONObject().apply {
                put("name", name)
                put("owner", me)
                put("members", JSONArray(allMembers))
            }
            val request = NetworkClient.createPostRequest("/create-group", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val groupId = JSONObject(body).getString("groupId")
                    
                    // Yerel DB'ye kaydet
                    val group = GroupEntity(
                        id = groupId,
                        name = name,
                        owner = me,
                        members = allMembers.joinToString(",")
                    )
                    db.groupDao().insertGroup(group)
                    
                    // Otomatik hoş geldin mesajı gönder
                    try {
                        messageRepository.sendGroupMessage(groupId, "Grup oluşturuldu")
                    } catch (e: Exception) {
                        // Mesaj gönderilemezse bile grup oluşturma başarılı sayılmalı
                    }
                    
                    Result.success(groupId)
                } else {
                    Result.failure(Exception("Server error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroup(groupId: String): GroupEntity? = db.groupDao().getGroupById(groupId)

    suspend fun saveGroup(group: GroupEntity) = db.groupDao().insertGroup(group)
    
    suspend fun updateLastMessage(groupId: String, text: String, ts: Long) {
        db.groupDao().updateLastMessage(groupId, text, ts)
    }

    suspend fun updateMutedStatus(groupId: String, isMuted: Boolean) {
        db.groupDao().updateMutedStatus(groupId, isMuted)
    }

    suspend fun syncGroupDetailsFromServer(groupId: String): Result<GroupEntity> = withContext(Dispatchers.IO) {
        try {
            val url = "${NetworkClient.TOKEN_SERVER_URL}/group-details?groupId=${java.net.URLEncoder.encode(groupId, "UTF-8")}"
            val request = NetworkClient.createGetRequest(url)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val group = GroupEntity(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        owner = json.getString("owner"),
                        members = json.getJSONArray("members").let { arr ->
                            List(arr.length()) { arr.getString(it) }.joinToString(",")
                        },
                        createdAt = json.optLong("createdAt", System.currentTimeMillis())
                    )
                    db.groupDao().insertGroup(group)
                    Result.success(group)
                } else {
                    Result.failure(IOException("Server error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transferAdmin(groupId: String, newAdmin: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val me = sessionPreferences.getCurrentIdentity() ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val json = JSONObject().apply {
                put("groupId", groupId)
                put("currentAdmin", me)
                put("newAdmin", newAdmin)
            }
            val request = NetworkClient.createPostRequest("/transfer-admin", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Yerel DB'yi güncelle
                    val group = db.groupDao().getGroupById(groupId)
                    group?.let {
                        db.groupDao().insertGroup(it.copy(owner = newAdmin))
                    }
                    Result.success(true)
                } else {
                    Result.failure(IOException("Server error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
