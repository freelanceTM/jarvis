package com.jarvis.assistant.domain.repository

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesStream(): Flow<List<Message>>
    suspend fun getRecentMessages(limit: Int = 10): List<Message>
    suspend fun getLastMessage(): Message?
    suspend fun insertMessage(message: Message): Long
    suspend fun clearHistory()
    suspend fun deleteMessageById(id: Long)
}

interface AIRepository {
    suspend fun generateResponse(
        prompt: String,
        systemPrompt: String,
        history: List<Message> = emptyList()
    ): Resource<String>
}

interface SettingsRepository {
    val systemPromptFlow: Flow<String>
    val speechRateFlow: Flow<Float>
    val speechPitchFlow: Flow<Float>
    val userNameFlow: Flow<String>
    val selectedModelFlow: Flow<String>
    
    suspend fun setSystemPrompt(prompt: String)
    suspend fun setSpeechRate(rate: Float)
    suspend fun setSpeechPitch(pitch: Float)
    suspend fun setUserName(name: String)
    suspend fun setSelectedModel(model: String)
    suspend fun resetDefaults()
}
