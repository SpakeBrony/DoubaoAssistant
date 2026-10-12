package com.spake.brony.doubaoassistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.spake.brony.doubaoassistant.data.local.dao.ChatHistoryDao
import com.spake.brony.doubaoassistant.data.local.entity.ChatMessageEntity
import com.spake.brony.doubaoassistant.data.local.entity.ChatTopicEntity

@Database(entities = [ChatTopicEntity::class, ChatMessageEntity::class], version = 1, exportSchema = false)
internal abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ChatHistoryDao

    companion object {
        const val NAME = "chat_history.db"

        fun create(context: Context): ChatHistoryDatabase = Room.databaseBuilder(
            context.applicationContext, ChatHistoryDatabase::class.java, NAME)
            // shortcut: 仅用于当前测试阶段，结构变化直接清空重建；正式保留历史前需配置迁移。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
