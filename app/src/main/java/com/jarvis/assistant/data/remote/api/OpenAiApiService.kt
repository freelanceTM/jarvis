package com.jarvis.assistant.data.remote.api

import com.jarvis.assistant.data.remote.dto.ChatCompletionRequest
import com.jarvis.assistant.data.remote.dto.ChatCompletionResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface OpenAiApiService {

    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}
