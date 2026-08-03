package com.dogu.livekit.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val remoteId: String? = null,
    val sender: String,
    val recipient: String,
    val content: String,
    val timestamp: Long,
    val isMine: Boolean,
    val isRead: Boolean = false
)
