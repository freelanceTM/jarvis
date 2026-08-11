package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "preferences",
    indices = [Index(value = ["pref_key"], unique = true)]
)
data class PreferenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "pref_key")
    val prefKey: String, // "sleep.time", "diet", "preferred_style"

    @ColumnInfo(name = "pref_value")
    val prefValue: String, // "23:00", "vegan", "formal"

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
