package com.jarvis.assistant.agent.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.agent.memory.model.MemoryItem
import com.jarvis.assistant.agent.memory.model.MemoryType

@Entity(
    tableName = "memories",
    indices = [Index(value = ["type"]), Index(value = ["importance"]), Index(value = ["last_accessed_at"])]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "type")
    val type: String, // FACT, PREFERENCE, EPISODIC, PROCEDURAL

    @ColumnInfo(name = "key_name")
    val keyName: String? = null,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "importance")
    val importance: Float = 0.5f,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 1,

    @ColumnInfo(name = "embedding_vector")
    val embeddingVector: String = "", // Сериализованный нормализованный float массив

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long = System.currentTimeMillis()
)

fun MemoryEntity.toDomain(calculatedScore: Float = 0f): MemoryItem = MemoryItem(
    id = id,
    type = try { MemoryType.valueOf(type) } catch (_: Exception) { MemoryType.FACT },
    key = keyName,
    content = content,
    importance = importance,
    confidence = confidence,
    accessCount = accessCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    relevanceScore = calculatedScore
)
