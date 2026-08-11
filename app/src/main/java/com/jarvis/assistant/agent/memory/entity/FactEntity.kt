package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "facts",
    indices = [Index(value = ["fact_key"], unique = true)]
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "fact_key")
    val factKey: String, // "user.name", "user.car", "user.city"

    @ColumnInfo(name = "fact_value")
    val factValue: String, // "Александр", "BMW M5"

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
