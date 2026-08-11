package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Слой 3: Semantic Memory (Факты о пользователе, предпочтения, знания)
 */
@Entity(
    tableName = "semantic_memory",
    indices = [Index(value = ["key_name"], unique = true), Index(value = ["category"])]
)
data class SemanticMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "key_name")
    val keyName: String, // например "user_name", "user_car", "city", "job"

    @ColumnInfo(name = "value_text")
    val valueText: String, // например "Алекс", "BMW M5", "Москва"

    @ColumnInfo(name = "category")
    val category: String = "general", // "profile", "preferences", "knowledge", "work"

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
