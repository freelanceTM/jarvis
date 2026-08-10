package com.jarvis.assistant.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    @SerialName("model")
    val model: String = "gpt-4o-mini",
    @SerialName("messages")
    val messages: List<ApiMessageDto>,
    @SerialName("temperature")
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 1000
)

@Serializable
data class ApiMessageDto(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String
)

@Serializable
data class ChatCompletionResponse(
    @SerialName("id")
    val id: String? = null,
    @SerialName("created")
    val created: Long? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("choices")
    val choices: List<ChoiceDto> = emptyList(),
    @SerialName("usage")
    val usage: UsageDto? = null
)

@Serializable
data class ChoiceDto(
    @SerialName("index")
    val index: Int = 0,
    @SerialName("message")
    val message: ApiMessageDto,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)

@Serializable
data class ApiErrorResponse(
    @SerialName("error")
    val error: ApiErrorDetail? = null
)

@Serializable
data class ApiErrorDetail(
    @SerialName("message")
    val message: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("code")
    val code: String? = null
)
