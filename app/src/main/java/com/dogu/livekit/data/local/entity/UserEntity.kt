package com.dogu.livekit.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val identity: String,
    val password: String? = null, // Yerel girişler için
    val isOnline: Boolean = false,
    val profilePhoto: String? = null,
    val currentRoom: String? = null,
    val publicKey: String? = null,
    val needsSync: Boolean = false, // Sunucuya gönderilmediyse true
    val isBlocked: Boolean = false,
    val isMuted: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
