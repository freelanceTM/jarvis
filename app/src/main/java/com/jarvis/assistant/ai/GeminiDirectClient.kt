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
data class OpenAiChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<OpenAiMessageDto>,
    @SerialName("temperature") val temperature: Double = 0.7
)

@Serializable
data class OpenAiMessageDto(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
data class OpenAiChatResponse(
    @SerialName("choices") val choices: List<OpenAiChoiceDto> = emptyList()
)

@Serializable
data class OpenAiChoiceDto(
    @SerialName("message") val message: OpenAiMessageDto
)

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
class UniversalAIClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val securityManager: SecurityManager,
    private val json: Json
) : AIClient {

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // Ваш персональный шлюз Cloudflare (США/Европа) + резервные шлюзы
    private val geminiGateways = listOf(
        "https://jarvis-gemini-gateway.isgenderdurdyyew95.workers.dev",
        "https://generativelanguage.googleapis.com",
        "https://gemini-proxy.freeflare.workers.dev"
    )

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> = withContext(Dispatchers.IO) {
        val apiKey = securityManager.getApiKey().trim()
        if (apiKey.isEmpty() || apiKey.length < 5) {
            return@withContext Resource.Error(
                IllegalStateException("Ключ не указан"),
                "Пожалуйста, введите API-ключ в настройках."
            )
        }

        try {
            if (apiKey.startsWith("gsk_")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                    model = "llama-3.3-70b-versatile",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else if (apiKey.startsWith("sk-or-")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
                    model = "google/gemini-2.0-flash-exp:free",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else if (apiKey.startsWith("sk-")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.openai.com/v1/chat/completions",
                    model = "gpt-4o-mini",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else {
                return@withContext callGeminiWithGatewayFallback(apiKey, prompt, systemPrompt, history)
            }
        } catch (e: SocketTimeoutException) {
            Resource.Error(e, "Таймаут подключения к AI. Проверьте интернет.")
        } catch (e: IOException) {
            Resource.Error(e, "Нет подключения к интернету.")
        } catch (e: Exception) {
            Resource.Error(e, "Ошибка связи с AI: ${e.localizedMessage}")
        }
    }

    private fun callGeminiWithGatewayFallback(
        apiKey: String,
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> {
        val contents = mutableListOf<GeminiContentDto>()
        history.takeLast(6).forEach { msg ->
            contents.add(
                GeminiContentDto(
                    role = if (msg.role == MessageRole.ASSISTANT) "model" else "user",
                    parts = listOf(GeminiPartDto(text = msg.text))
                )
            )
        }
        if (contents.lastOrNull()?.parts?.firstOrNull()?.text != prompt) {
            contents.add(GeminiContentDto(role = "user", parts = listOf(GeminiPartDto(text = prompt))))
        }

        val systemInstruction = if (systemPrompt.isNotBlank()) {
            GeminiSystemInstructionDto(parts = listOf(GeminiPartDto(text = systemPrompt)))
        } else null

        val requestBodyObj = GeminiRequestDto(contents, systemInstruction)
        val jsonBody = json.encodeToString(GeminiRequestDto.serializer(), requestBodyObj)

        var lastErrorMessage = "Не удалось связаться с Gemini"

        for (baseUrl in geminiGateways) {
            try {
                val requestUrl = "$baseUrl/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(requestUrl)
                    .post(jsonBody.toRequestBody(mediaType))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val geminiResponse = json.decodeFromString(GeminiResponseDto.serializer(), responseBody)
                    val answer = geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                    if (!answer.isNullOrEmpty()) {
                        return Resource.Success(answer)
                    }
                } else {
                    val code = response.code
                    if (code == 400 || code == 403) {
                        lastErrorMessage = "Ошибка авторизации Google Gemini ($code)."
                        continue
                    } else if (code == 429) {
                        return Resource.Error(IllegalStateException("429"), "Превышен лимит запросов Gemini (подождите 30 сек).")
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        return Resource.Error(IllegalStateException("Gateway Error"), lastErrorMessage)
    }

    private fun callOpenAiCompatible(
        endpointUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> {
        val messages = mutableListOf<OpenAiMessageDto>()
        if (systemPrompt.isNotBlank()) {
            messages.add(OpenAiMessageDto("system", systemPrompt))
        }
        history.takeLast(6).forEach { msg ->
            messages.add(OpenAiMessageDto(if (msg.role == MessageRole.ASSISTANT) "assistant" else "user", msg.text))
        }
        if (messages.lastOrNull()?.content != prompt) {
            messages.add(OpenAiMessageDto("user", prompt))
        }

        val requestObj = OpenAiChatRequest(model = model, messages = messages)
        val jsonBody = json.encodeToString(OpenAiChatRequest.serializer(), requestObj)

        val request = Request.Builder()
            .url(endpointUrl)
            .post(jsonBody.toRequestBody(mediaType))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (response.isSuccessful && responseBody.isNotEmpty()) {
            val openAiResponse = json.decodeFromString(OpenAiChatResponse.serializer(), responseBody)
            val answer = openAiResponse.choices.firstOrNull()?.message?.content?.trim()
            return if (!answer.isNullOrEmpty()) Resource.Success(answer) else Resource.Error(IllegalStateException("Пустой ответ"), "AI вернул пустой ответ.")
        } else {
            return Resource.Error(IllegalStateException("HTTP ${response.code}: $responseBody"), "Ошибка AI сервиса (${response.code})")
        }
    }
}
