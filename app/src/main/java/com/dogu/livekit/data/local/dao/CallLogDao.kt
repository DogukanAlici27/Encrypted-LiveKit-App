package com.dogu.livekit.data.local.dao

import androidx.room.*
import com.dogu.livekit.data.local.entity.CallLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<CallLogEntity>>

    @Insert
    suspend fun insertLog(log: CallLogEntity)

    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()
}
