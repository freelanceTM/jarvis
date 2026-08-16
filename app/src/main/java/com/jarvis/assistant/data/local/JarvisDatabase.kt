package com.jarvis.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.memory.dao.*
import com.jarvis.assistant.agent.memory.entity.*
import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        MemoryEntity::class,
        FactEntity::class,
        PreferenceEntity::class,
        ProcedureEntity::class,
        AutomationEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun factDao(): FactDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun procedureDao(): ProcedureDao
    abstract fun automationDao(): AutomationDao
}
