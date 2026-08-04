package com.dogu.livekit.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val owner: String,
    val members: String, // Virgülle ayrılmış identity listesi
    val lastMessage: String? = null,
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val isMuted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
