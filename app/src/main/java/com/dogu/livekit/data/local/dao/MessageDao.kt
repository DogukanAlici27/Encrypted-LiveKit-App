package com.dogu.livekit.data.local.dao

import androidx.room.*
import com.dogu.livekit.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages 
        WHERE (LOWER(sender) = LOWER(:me) AND LOWER(recipient) = LOWER(:user) AND groupId IS NULL) 
           OR (LOWER(sender) = LOWER(:user) AND LOWER(recipient) = LOWER(:me) AND groupId IS NULL) 
        ORDER BY timestamp ASC
    """)
    fun getChatMessages(me: String, user: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE groupId = :groupId
        ORDER BY timestamp ASC
    """)
    fun getGroupMessages(groupId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET isRead = 1 WHERE LOWER(sender) = LOWER(:sender) AND LOWER(recipient) = LOWER(:me) AND isRead = 0")
    suspend fun markAsRead(sender: String, me: String)

    @Query("UPDATE messages SET isRead = 1 WHERE groupId = :groupId AND isRead = 0 AND LOWER(sender) != LOWER(:me)")
    suspend fun markGroupAsRead(groupId: String, me: String)

    @Query("UPDATE messages SET isRead = 1 WHERE LOWER(sender) = LOWER(:me) AND LOWER(recipient) = LOWER(:recipient) AND isRead = 0")
    suspend fun markSentMessagesAsRead(me: String, recipient: String)

    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages 
            WHERE (LOWER(sender) = LOWER(:me) OR LOWER(recipient) = LOWER(:me) OR groupId IS NOT NULL)
            GROUP BY 
                CASE 
                    WHEN groupId IS NOT NULL THEN groupId 
                    WHEN LOWER(sender) = LOWER(:me) THEN LOWER(recipient) 
                    ELSE LOWER(sender) 
                END
        )
        ORDER BY timestamp DESC
    """)
    fun getLastMessages(me: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE (sender = :me AND recipient = :user) OR (sender = :user AND recipient = :me)")
    suspend fun deleteConversation(me: String, user: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    suspend fun deleteGroupMessages(groupId: String)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("UPDATE messages SET remoteId = :remoteId WHERE id = :localId")
    suspend fun updateRemoteId(localId: Long, remoteId: String)
}
