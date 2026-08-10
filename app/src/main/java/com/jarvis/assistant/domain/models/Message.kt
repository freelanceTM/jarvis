package com.jarvis.assistant.domain.models

data class Message(
    val id: Long = 0L,
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
