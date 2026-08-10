package com.jarvis.assistant.ai

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GeminiContentDto(
    @SerialName("role") val role: String,
    @SerialName("parts") val parts: List<GeminiPartDto>
)

@Serializable
data class GeminiPartDto(
    @SerialName("text") val text: String
)

@Serializable
data class GeminiSystemInstructionDto(
    @SerialName("parts") val parts: List<GeminiPartDto>
)

@Serializable
data class GeminiRequestDto(
    @SerialName("contents") val contents: List<GeminiContentDto>,
    @SerialName("systemInstruction") val systemInstruction: GeminiSystemInstructionDto? = null
)

@Serializable
data class GeminiResponseDto(
    @SerialName("candidates") val candidates: List<GeminiCandidateDto> = emptyList()
)

@Serializable
data class GeminiCandidateDto(
    @SerialName("content") val content: GeminiContentDto? = null
)

@Singleton
class GeminiDirectClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val securityManager: SecurityManager,
    private val json: Json
) : AIClient {

    private val baseGeminiUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> = withContext(Dispatchers.IO) {
        val apiKey = securityManager.getApiKey()
        if (!securityManager.hasValidApiKey()) {
            return@withContext Resource.Error(
                IllegalStateException("Gemini API-ключ не настроен"),
                "Укажите ключ Gemini API в настройках"
            )
        }

        try {
            // Формируем историю диалога для Gemini (роли: 'user' и 'model')
            val contents = mutableListOf<GeminiContentDto>()
            
            history.takeLast(6).forEach { msg ->
                val geminiRole = if (msg.role == MessageRole.ASSISTANT) "model" else "user"
                contents.add(
                    GeminiContentDto(
                        role = geminiRole,
                        parts = listOf(GeminiPartDto(text = msg.text))
                    )
                )
            }

            if (contents.lastOrNull()?.parts?.firstOrNull()?.text != prompt) {
                contents.add(
                    GeminiContentDto(
                        role = "user",
                        parts = listOf(GeminiPartDto(text = prompt))
                    )
                )
            }

            val systemInstruction = if (systemPrompt.isNotBlank()) {
                GeminiSystemInstructionDto(
                    parts = listOf(GeminiPartDto(text = systemPrompt))
                )
            } else null

            val requestBodyObj = GeminiRequestDto(
                contents = contents,
                systemInstruction = systemInstruction
            )

            val jsonBody = json.encodeToString(GeminiRequestDto.serializer(), requestBodyObj)
            val mediaType = "application/json; charset=utf-8".toMediaType()

            // Модель gemini-1.5-flash или gemini-2.0-flash
            val targetModel = "gemini-1.5-flash"
            val requestUrl = "$baseGeminiUrl/$targetModel:generateContent?key=$apiKey"

            val httpRequest = Request.Builder()
                .url(requestUrl)
                .post(jsonBody.toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful && responseBody.isNotEmpty()) {
                val geminiResponse = json.decodeFromString(GeminiResponseDto.serializer(), responseBody)
                val answer = geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()

                if (!answer.isNullOrEmpty()) {
                    Resource.Success(answer)
                } else {
                    Resource.Error(IllegalStateException("Пустой ответ от Gemini"), "Пустой ответ")
                }
            } else {
                val code = response.code
                val userMsg = when (code) {
                    400 -> "Неверный запрос или ключ Gemini API."
                    403 -> "Ключ Gemini заблокирован или нет доступа."
                    429 -> "Превышен лимит запросов Gemini (Rate Limit)."
                    else -> "Ошибка сервера Gemini ($code)."
                }
                Resource.Error(IllegalStateException("HTTP $code: $responseBody"), userMsg)
            }
        } catch (e: SocketTimeoutException) {
            Resource.Error(e, "Таймаут подключения к Gemini. Проверьте интернет.")
        } catch (e: IOException) {
            Resource.Error(e, "Нет подключения к интернету.")
        } catch (e: Exception) {
            Resource.Error(e, "Ошибка связи с Gemini API.")
        }
    }
}
