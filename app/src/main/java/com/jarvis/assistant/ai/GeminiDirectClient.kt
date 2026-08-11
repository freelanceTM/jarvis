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
            // 1. OpenRouter (Использует универсальную бесплатную модель openrouter/free)
            if (apiKey.startsWith("sk-or-")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
                    model = "openrouter/free",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            }
            // 2. Groq (Llama 3.3 70B)
            else if (apiKey.startsWith("gsk_")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                    model = "llama-3.3-70b-versatile",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            }
            // 3. OpenAI (GPT-4o Mini)
            else if (apiKey.startsWith("sk-")) {
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.openai.com/v1/chat/completions",
                    model = "gpt-4o-mini",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            }
            // 4. Google Gemini API
            else {
                return@withContext callDirectGemini(apiKey, prompt, systemPrompt, history)
            }
        } catch (e: SocketTimeoutException) {
            Resource.Error(e, "Таймаут подключения к AI. Проверьте интернет.")
        } catch (e: IOException) {
            Resource.Error(e, "Ошибка сети при запросе к AI.")
        } catch (e: Exception) {
            Resource.Error(e, "Ошибка AI: ${e.localizedMessage}")
        }
    }

    private fun callDirectGemini(
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

        val requestUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey"

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
            return if (!answer.isNullOrEmpty()) {
                Resource.Success(answer)
            } else {
                Resource.Error(IllegalStateException("Пустой ответ"), "AI вернул пустой ответ.")
            }
        } else {
            val code = response.code
            val userMsg = when (code) {
                400, 403 -> "Google блокирует запросы из вашего региона без VPN. Включите VPN или используйте ключ OpenRouter (sk-or-...)."
                429 -> "Лимит запросов Gemini исчерпан. Пожалуйста, подождите 30 секунд."
                else -> "Ошибка сервера AI ($code)."
            }
            return Resource.Error(IllegalStateException("HTTP $code: $responseBody"), userMsg)
        }
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
            .header("HTTP-Referer", "https://github.com/freelanceTM/jarvis")
            .header("X-Title", "JARVIS Assistant")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (response.isSuccessful && responseBody.isNotEmpty()) {
            val openAiResponse = json.decodeFromString(OpenAiChatResponse.serializer(), responseBody)
            val answer = openAiResponse.choices.firstOrNull()?.message?.content?.trim()
            return if (!answer.isNullOrEmpty()) {
                Resource.Success(answer)
            } else {
                Resource.Error(IllegalStateException("Пустой ответ"), "AI вернул пустой ответ.")
            }
        } else {
            return Resource.Error(IllegalStateException("HTTP ${response.code}: $responseBody"), "Ошибка AI сервиса (${response.code})")
        }
    }
}
