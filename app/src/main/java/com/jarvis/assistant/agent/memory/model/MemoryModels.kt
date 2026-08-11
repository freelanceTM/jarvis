package com.jarvis.assistant.agent.memory.model

import kotlinx.serialization.Serializable

enum class MemoryType {
    FACT,        // Факты о пользователе (имя, машина, город, работа)
    PREFERENCE,  // Предпочтения и привычки (время сна, любимый стиль, язык)
    EPISODIC,    // События и важные обсуждения
    PROCEDURAL   // Макросы и сценарии автоматизации (триггер -> действия)
}

@Serializable
data class ExtractedMemory(
    val shouldRemember: Boolean,
    val type: String = "FACT",
    val key: String? = null,
    val value: String? = null,
    val content: String = "",
    val importance: Float = 0.5f,
    val confidence: Float = 0.9f
)

data class MemoryItem(
    val id: Long = 0L,
    val type: MemoryType,
    val key: String? = null,
    val content: String,
    val importance: Float = 0.5f,
    val confidence: Float = 1.0f,
    val accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val relevanceScore: Float = 0f
)
