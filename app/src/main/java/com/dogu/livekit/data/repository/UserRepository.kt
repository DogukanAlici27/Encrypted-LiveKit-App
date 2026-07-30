package com.dogu.livekit.data.repository

import com.dogu.livekit.data.AppDatabase
import com.dogu.livekit.data.entity.CallLogEntity
import com.dogu.livekit.data.entity.UserEntity
import com.dogu.livekit.network.NetworkClient
import com.dogu.livekit.pref.SessionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val sessionPreferences: SessionPreferences
) {

    suspend fun syncUsers(usersArray: JSONArray) = withContext(Dispatchers.IO) {
        // Sunucuda olmayanları söndür
        db.userDao().resetOnlineStatuses()

        val entities = mutableListOf<UserEntity>()
        for (i in 0 until usersArray.length()) {
            val user = usersArray.getJSONObject(i)
            val identity = user.getString("identity").trim()
            val existing = db.userDao().getUser(identity)
            
            val rawRoom = user.optString("currentRoom", "")
            val currentRoom = if (rawRoom == "null" || rawRoom.isEmpty()) null else rawRoom
            
            entities.add(UserEntity(
                identity = identity,
                password = existing?.password,
                isOnline = user.optBoolean("isOnline", false),
                profilePhoto = user.optString("profilePhoto", ""),
                currentRoom = currentRoom,
                publicKey = user.optString("publicKey", ""),
                needsSync = false,
                isBlocked = existing?.isBlocked ?: false
            ))
        }
        if (entities.isNotEmpty()) db.userDao().insertUsers(entities)
    }

    suspend fun syncUnsyncedUsers(fcmToken: String) = withContext(Dispatchers.IO) {
        val currentIdentity = sessionPreferences.getCurrentIdentity()
        
        val unsynced = db.userDao().getUnsyncedUsers()
        unsynced.forEach { user ->
            val isMe = user.identity.trim() == currentIdentity?.trim()
            val result = auth("register", user.identity, user.password ?: "", fcmToken, user.publicKey, isOnline = isMe)
            
            if (result.isSuccess || result.exceptionOrNull()?.message?.contains("409") == true) {
                db.userDao().markAsSynced(user.identity)
            }
        }
    }

    suspend fun saveLocalUser(identity: String, password: String, publicKey: String?, needsSync: Boolean) = withContext(Dispatchers.IO) {
        val user = UserEntity(identity = identity, password = password, publicKey = publicKey, needsSync = needsSync, isOnline = true)
        db.userDao().insertUsers(listOf(user))
    }

    suspend fun getLocalUsers(): JSONArray = withContext(Dispatchers.IO) {
        val users = db.userDao().getAllUsers().first()
        val array = JSONArray()
        users.forEach { user ->
            array.put(JSONObject().apply {
                put("identity", user.identity)
                put("isOnline", user.isOnline)
                put("profilePhoto", user.profilePhoto)
                put("currentRoom", user.currentRoom)
                put("publicKey", user.publicKey)
            })
        }
        array
    }

    suspend fun fetchLocalUser(identity: String): UserEntity? = withContext(Dispatchers.IO) {
        db.userDao().getUser(identity)
    }

    suspend fun saveCallLog(target: String, type: String) = withContext(Dispatchers.IO) {
        val log = CallLogEntity(target = target, type = type)
        db.callLogDao().insertLog(log)
    }

    suspend fun deleteLocalUser(identity: String) = withContext(Dispatchers.IO) {
        db.userDao().deleteByIdentity(identity)
    }

    suspend fun deleteUserOnServer(identity: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("identity", identity) }
            val request = NetworkClient.createPostRequest("/delete-user", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException("Delete error: ${response.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun changePasswordOnServer(identity: String, oldPass: String, newPass: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identity", identity)
                put("oldPassword", oldPass)
                put("newPassword", newPass)
            }
            val request = NetworkClient.createPostRequest("/change-password", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException(response.body?.string() ?: "Error"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateLocalPassword(identity: String, newPass: String, needsSync: Boolean) = withContext(Dispatchers.IO) {
        db.userDao().updatePassword(identity, newPass, if (needsSync) 1 else 0)
    }

    suspend fun restoreCurrentUserOnServer(identity: String, fcmToken: String) = withContext(Dispatchers.IO) {
        val user = db.userDao().getUser(identity)
        if (user != null && user.password != null) {
            val result = auth("register", user.identity, user.password, fcmToken, user.publicKey, isOnline = true)
            if (result.isSuccess || result.exceptionOrNull()?.message?.contains("409") == true) {
                db.userDao().markAsSynced(user.identity)
            }
        }
    }

    suspend fun auth(mode: String, identity: String, password: String, fcmToken: String, publicKey: String? = null, isOnline: Boolean = true): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identity", identity)
                put("password", password)
                put("fcmToken", fcmToken)
                put("isOnline", isOnline)
                if (publicKey != null) put("publicKey", publicKey)
            }
            val endpoint = if (mode == "register") "/register" else "/login"
            val request = NetworkClient.createPostRequest(endpoint, json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(JSONObject())
                else Result.failure(IOException(response.body?.string() ?: "Error"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateUserPhoto(identity: String, profilePhoto: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identity", identity)
                put("profilePhoto", profilePhoto)
            }
            val request = NetworkClient.createPostRequest("/update-user", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Update error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUsers(): Result<JSONArray> = withContext(Dispatchers.IO) {
        try {
            val url = "${NetworkClient.TOKEN_SERVER_URL}/users"
            val request = NetworkClient.createGetRequest(url)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(JSONArray(response.body?.string() ?: "[]"))
                else Result.failure(IOException("Error: ${response.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun fetchToken(identity: String, target: String?, manualRoom: String?, encryptedKeysJson: String? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            var url = "${NetworkClient.TOKEN_SERVER_URL}/token?identity=$identity"
            if (target != null) url += "&target=$target"
            if (manualRoom != null) url += "&room=$manualRoom"
            if (encryptedKeysJson != null) url += "&keys=${java.net.URLEncoder.encode(encryptedKeysJson, "UTF-8")}"
            val request = NetworkClient.createGetRequest(url)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(JSONObject(response.body?.string() ?: "{}"))
                else Result.failure(IOException("Error: ${response.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun sendHeartbeat(identity: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val currentRoom = com.dogu.livekit.call.CallManager.room
            val roomName = if (currentRoom != null && currentRoom.state == io.livekit.android.room.Room.State.CONNECTED) {
                currentRoom.name
            } else {
                "" // Boş string göndererek sunucuda odayı temizle
            }
            val json = JSONObject().apply {
                put("identity", identity)
                put("room", roomName)
            }
            val request = NetworkClient.createPostRequest("/heartbeat", json)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException("HTTP Error: ${response.code}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun sendOffline(identity: String) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("identity", identity) }
            val request = NetworkClient.createPostRequest("/offline", json)
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {}
    }

    suspend fun updateBlockedStatus(identity: String, isBlocked: Boolean) = withContext(Dispatchers.IO) {
        db.userDao().updateBlockedStatus(identity, isBlocked)
    }

    suspend fun getBlockedUsers(): Flow<List<UserEntity>> =
        db.userDao().getBlockedUsers()
}
