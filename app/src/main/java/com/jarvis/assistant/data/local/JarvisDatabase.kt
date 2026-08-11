package com.jarvis.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.jarvis.assistant.agent.memory.dao.ProceduralMemoryDao
import com.jarvis.assistant.agent.memory.dao.SemanticMemoryDao
import com.jarvis.assistant.agent.memory.entity.ProceduralMemoryEntity
import com.jarvis.assistant.agent.memory.entity.SemanticMemoryEntity
import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        SemanticMemoryEntity::class,
        ProceduralMemoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun semanticMemoryDao(): SemanticMemoryDao
    abstract fun proceduralMemoryDao(): ProceduralMemoryDao
}
