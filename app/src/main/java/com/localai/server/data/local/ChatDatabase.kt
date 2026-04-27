package com.localai.server.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.localai.server.data.local.dao.ConversationDao
import com.localai.server.data.local.dao.MessageDao
import com.localai.server.data.local.entity.Conversation
import com.localai.server.data.local.entity.Message

@Database(
    entities = [Conversation::class, Message::class],
    version = 1,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    
    companion object {
        const val DATABASE_NAME = "chat_database"
    }
}
