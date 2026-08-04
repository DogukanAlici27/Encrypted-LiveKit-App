package com.dogu.livekit.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dogu.livekit.data.local.dao.CallLogDao
import com.dogu.livekit.data.local.dao.GroupDao
import com.dogu.livekit.data.local.dao.MessageDao
import com.dogu.livekit.data.local.dao.UserDao
import com.dogu.livekit.data.local.entity.CallLogEntity
import com.dogu.livekit.data.local.entity.GroupEntity
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.local.entity.UserEntity

@Database(entities = [UserEntity::class, CallLogEntity::class, MessageEntity::class, GroupEntity::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun callLogDao(): CallLogDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "livekit_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}