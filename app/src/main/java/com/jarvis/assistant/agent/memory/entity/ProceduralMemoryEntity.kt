package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Слой 4: Procedural Memory (Макросы, сценарии автоматизации, привычки)
 * Пример: триггер "работа" -> действия: [open_app telegram, open_app chrome, set_volume 40]
 */
@Entity(
    tableName = "procedural_memory",
    indices = [Index(value = ["trigger_phrase"], unique = true)]
)
data class ProceduralMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "trigger_phrase")
    val triggerPhrase: String, // "работа", "утро", "сон", "тренировка"

    @ColumnInfo(name = "actions_json")
    val actionsJson: String, // сериализованный список действий ToolCall

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
