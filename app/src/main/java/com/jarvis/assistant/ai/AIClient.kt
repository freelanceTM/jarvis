package com.jarvis.assistant.ai

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.data.preferences.SettingsDataStore
import com.jarvis.assistant.data.remote.api.OpenAiApiService
import com.jarvis.assistant.data.remote.dto.ChatCompletionRequest
import com.jarvis.assistant.domain.models.Message
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

interface AIClient {
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String>
}

@Singleton
class OpenAiDirectClient @Inject constructor(
    private val apiService: OpenAiApiService,
    private val promptManager: PromptManager,
    private val securityManager: SecurityManager,
    private val settingsDataStore: SettingsDataStore
) : AIClient {

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> {
        if (!securityManager.hasValidApiKey()) {
            return Resource.Error(
                IllegalStateException("API-ключ не настроен. Перейдите в настройки."),
                "API-ключ отсутствует"
            )
        }

        return try {
            val selectedModel = settingsDataStore.selectedModelFlow.first()
            val messageList = promptManager.buildChatPrompt(
                systemPrompt = systemPrompt,
                userPrompt = prompt,
                recentHistory = history
            )

            val request = ChatCompletionRequest(
                model = selectedModel,
                messages = messageList,
                temperature = 0.7,
                maxTokens = 800
            )

            val response = apiService.createChatCompletion(request)

            if (response.isSuccessful && response.body() != null) {
                val answer = response.body()?.choices?.firstOrNull()?.message?.content?.trim()
                if (!answer.isNullOrEmpty()) {
                    Resource.Success(answer)
                } else {
                    Resource.Error(IllegalStateException("Пустой ответ от AI-модели"), "Пустой ответ")
                }
            } else {
                val errorCode = response.code()
                val userMessage = when (errorCode) {
                    401 -> "Неверный API-ключ. Проверьте настройки."
                    429 -> "Превышен лимит запросов к AI (Rate Limit)."
                    500, 502, 503 -> "Сервер AI временно недоступен."
                    else -> "AI временно недоступен (Код $errorCode)."
                }
                Resource.Error(
                    IllegalStateException("HTTP $errorCode: ${response.message()}"),
                    userMessage
                )
            }
        } catch (e: SocketTimeoutException) {
            Resource.Error(e, "Таймаут подключения к AI. Проверьте интернет.")
        } catch (e: IOException) {
            Resource.Error(e, "Нет подключения к интернету.")
        } catch (e: Exception) {
            Resource.Error(e, "Непредвиденная ошибка при связи с AI.")
        }
    }
}
