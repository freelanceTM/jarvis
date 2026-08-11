package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "procedures",
    indices = [Index(value = ["trigger_phrase"], unique = true)]
)
data class ProcedureEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "trigger_phrase")
    val triggerPhrase: String, // "работа", "утро", "сон"

    @ColumnInfo(name = "actions_json")
    val actionsJson: String, // сериализованный список действий ToolCall

    @ColumnInfo(name = "execution_count")
    val executionCount: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
