package com.jarvis.assistant.domain.models

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromString(value: String): MessageRole {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: USER
        }
    }
}
