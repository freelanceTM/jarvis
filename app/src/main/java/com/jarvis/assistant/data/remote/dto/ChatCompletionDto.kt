package com.jarvis.assistant.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiMessageDto(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String
)
