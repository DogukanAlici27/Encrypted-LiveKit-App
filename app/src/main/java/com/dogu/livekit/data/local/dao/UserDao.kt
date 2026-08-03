package com.dogu.livekit.data.local.dao

import androidx.room.*
import com.dogu.livekit.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY identity ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM users WHERE identity = :identity LIMIT 1")
    suspend fun getUser(identity: String): UserEntity?

    @Query("SELECT * FROM users WHERE needsSync = 1")
    suspend fun getUnsyncedUsers(): List<UserEntity>

    @Query("UPDATE users SET needsSync = 0 WHERE identity = :identity")
    suspend fun markAsSynced(identity: String)

    @Query("UPDATE users SET password = :newPassword, needsSync = :needsSync WHERE identity = :identity")
    suspend fun updatePassword(identity: String, newPassword: String, needsSync: Int)

    @Query("DELETE FROM users WHERE identity = :identity")
    suspend fun deleteByIdentity(identity: String)

    @Query("UPDATE users SET isOnline = 0, currentRoom = NULL")
    suspend fun resetOnlineStatuses()

    @Query("UPDATE users SET isBlocked = :isBlocked WHERE identity = :identity")
    suspend fun updateBlockedStatus(identity: String, isBlocked: Boolean)

    @Query("SELECT * FROM users WHERE isBlocked = 1")
    fun getBlockedUsers(): Flow<List<UserEntity>>
}
