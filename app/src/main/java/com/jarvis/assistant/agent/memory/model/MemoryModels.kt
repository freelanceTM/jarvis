package com.jarvis.assistant.agent.memory.model

import kotlinx.serialization.Serializable

enum class MemoryType {
    FACT,        // Факты о пользователе (имя, машина, город, работа, контакты)
    PREFERENCE,  // Предпочтения и привычки (время сна, стиль, любимые блюда)
    EPISODIC,    // Важные события, вехи, договоренности
    PROCEDURAL   // Сценарии и макросы автоматизации (триггер -> цепочка действий)
}

enum class GovernanceAction {
    STORE_NEW,      // Новое воспоминание
    UPDATE_EXISTING,// Обновление/разрешение конфликта
    DISCARD,        // Игнорировать (мусор/общий вопрос)
    DELETE_FORGET   // Явное удаление ("забудь...")
}

@Serializable
data class ExtractedMemory(
    val shouldRemember: Boolean,
    val type: String = "FACT",
    val key: String? = null,
    val value: String? = null,
    val content: String = "",
    val importance: Float = 0.5f,
    val confidence: Float = 0.9f,
    val governanceAction: String = "STORE_NEW"
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

data class ForgetResult(
    val isSuccess: Boolean,
    val deletedCount: Int,
    val deletedSummaries: List<String>,
    val confirmationMessage: String
)
