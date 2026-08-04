package com.dogu.livekit.data.local.dao

import androidx.room.*
import com.dogu.livekit.data.local.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY lastMessageTimestamp DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("UPDATE groups SET lastMessage = :text, lastMessageTimestamp = :ts WHERE id = :groupId")
    suspend fun updateLastMessage(groupId: String, text: String, ts: Long)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE groups SET isMuted = :isMuted WHERE id = :groupId")
    suspend fun updateMutedStatus(groupId: String, isMuted: Boolean)
}
