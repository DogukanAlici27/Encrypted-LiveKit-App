package com.dogu.livekit.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val target: String,
    val type: String, // "INCOMING", "OUTGOING", "MISSED", "REJECTED"
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Long = 0 // saniye cinsinden
)
