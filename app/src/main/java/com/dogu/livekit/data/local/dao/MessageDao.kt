package com.dogu.livekit.data.local.dao

import androidx.room.*
import com.dogu.livekit.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages 
        WHERE (sender = :me AND recipient = :user) 
           OR (sender = :user AND recipient = :me) 
        ORDER BY timestamp ASC
    """)
    fun getChatMessages(me: String, user: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET isRead = 1 WHERE sender = :sender AND recipient = :me AND isRead = 0")
    suspend fun markAsRead(sender: String, me: String)

    @Query("UPDATE messages SET isRead = 1 WHERE sender = :me AND recipient = :recipient AND isRead = 0")
    suspend fun markSentMessagesAsRead(me: String, recipient: String)

    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages 
            WHERE sender = :me OR recipient = :me 
            GROUP BY CASE WHEN sender = :me THEN recipient ELSE sender END
        )
        ORDER BY timestamp DESC
    """)
    fun getLastMessages(me: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE (sender = :me AND recipient = :user) OR (sender = :user AND recipient = :me)")
    suspend fun deleteConversation(me: String, user: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Delete
    suspend fun delete(message: MessageEntity)
}
