package com.dogu.livekit.data.repository

import com.dogu.livekit.data.AppDatabase
import com.dogu.livekit.data.entity.CallLogEntity
import com.dogu.livekit.data.entity.UserEntity
import com.dogu.livekit.network.NetworkClient
import com.dogu.livekit.pref.SessionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
                // /users endpoint'i isBlocked dönüyor; onu koru, yoksa mevcutu koru
                isBlocked = if (user.has("isBlocked")) user.optBoolean("isBlocked", false)
                else existing?.isBlocked ?: false
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
                ""
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

    // -------------------------------------------------------------------
    // ENGELLEME — LOCAL DB
    // -------------------------------------------------------------------

    /**
     * Local DB'de engel durumunu günceller.
     * Burası sadece Room'a yazar. Server sync için sendBlockToServer() çağrılmalı.
     */
    suspend fun updateBlockedStatus(identity: String, isBlocked: Boolean) = withContext(Dispatchers.IO) {
        db.userDao().updateBlockedStatus(identity, isBlocked)
    }

    fun getBlockedUsers(): Flow<List<UserEntity>> =
        db.userDao().getBlockedUsers()

    // -------------------------------------------------------------------
    // ENGELLEME — SUNUCU SYNC
    // -------------------------------------------------------------------

    /**
     * Engelleme/kaldırma kararını sunucuya bildirir.
     * POST /block-user { myIdentity, targetIdentity, isBlocked }
     *
     * Ağ yoksa hata dönülür; ViewModel bu durumda sadece log basar,
     * local değişiklik zaten DB'ye yazılmıştır.
     */
    suspend fun sendBlockToServer(myIdentity: String, targetIdentity: String, isBlocked: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("myIdentity", myIdentity)
                    put("targetIdentity", targetIdentity)
                    put("isBlocked", isBlocked)
                }
                val request = NetworkClient.createPostRequest("/block-user", json)
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(true)
                    else Result.failure(IOException("Block sync hatası: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Sunucudaki blok listesini çekip local DB ile senkronize eder.
     * GET /my-blocks?identity=ahmet  →  { blockedUsers: ["mehmet","ayse"] }
     *
     * Strateji:
     *  1. Server'daki engelleri al.
     *  2. Local'de engelli ama server'da değil → local'i server'a zorla (sendBlockToServer).
     *  3. Server'da engelli ama local'de değil → local DB'yi güncelle.
     *
     * Böylece her iki taraf her zaman senkronize kalır.
     */
    suspend fun syncBlockedUsersFromServer(myIdentity: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${NetworkClient.TOKEN_SERVER_URL}/my-blocks?identity=$myIdentity"
                val request = NetworkClient.createGetRequest(url)
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext Result.failure<Unit>(IOException("Blok listesi çekilemedi: ${response.code}"))
                }

                val body = response.body?.string() ?: "{}"
                response.close()

                val serverBlockedList = JSONObject(body).optJSONArray("blockedUsers") ?: JSONArray()

                val serverBlocked = mutableSetOf<String>()
                for (i in 0 until serverBlockedList.length()) {
                    serverBlocked.add(serverBlockedList.getString(i))
                }

                val localBlocked = db.userDao().getBlockedUsers().first()
                    .map { it.identity }
                    .toSet()

                // Local'de var, server'da yok → server'a gönder
                val onlyInLocal = localBlocked - serverBlocked
                for (identity in onlyInLocal) {
                    sendBlockToServer(myIdentity, identity, true)
                }

                // Server'da var, local'de yok → local DB'yi güncelle
                val onlyOnServer = serverBlocked - localBlocked
                for (identity in onlyOnServer) {
                    db.userDao().updateBlockedStatus(identity, true)
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}