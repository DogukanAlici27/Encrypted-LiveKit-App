package com.dogu.livekit.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object UserRepository {

    suspend fun auth(mode: String, identity: String, password: String, fcmToken: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identity", identity)
                put("password", password)
                put("fcmToken", fcmToken)
            }
            val endpoint = if (mode == "register") "/register" else "/login"
            val request = NetworkClient.createPostRequest(endpoint, json)
            
            NetworkClient.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(JSONObject()) 
                } else {
                    val errorMsg = response.body?.string() ?: "Error code: ${response.code}"
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserPhoto(identity: String, profilePhoto: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identity", identity)
                put("profilePhoto", profilePhoto)
            }
            val request = NetworkClient.createPostRequest("/update-user", json)
            NetworkClient.httpClient.newCall(request).execute().use { response ->
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
            NetworkClient.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    Result.success(JSONArray(body))
                } else {
                    Result.failure(IOException("Error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchToken(identity: String, target: String?, manualRoom: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            var url = "${NetworkClient.TOKEN_SERVER_URL}/token?identity=$identity"
            if (target != null) url += "&target=$target"
            if (manualRoom != null) url += "&room=$manualRoom"
            
            val request = NetworkClient.createGetRequest(url)
            NetworkClient.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    Result.success(JSONObject(body))
                } else {
                    Result.failure(IOException("Error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendHeartbeat(identity: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val currentRoom = com.dogu.livekit.call.CallManager.room
            // Sadece gerçekten bağlıysa oda ismini gönder
            val roomName = if (currentRoom != null && currentRoom.state == io.livekit.android.room.Room.State.CONNECTED) {
                currentRoom.name
            } else {
                null
            }

            val json = JSONObject().apply { 
                put("identity", identity)
                if (roomName != null) {
                    put("room", roomName)
                } else {
                    put("room", "") // Boş göndererek sunucuya "odada değilim" mesajı veriyoruz
                }
            }
            val request = NetworkClient.createPostRequest("/heartbeat", json)
            NetworkClient.httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Status: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendOffline(identity: String) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("identity", identity) }
            val request = NetworkClient.createPostRequest("/offline", json)
            NetworkClient.httpClient.newCall(request).execute().close()
        } catch (e: Exception) {}
    }
}
