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
    @SerialName("temperature") val temperature: Double = 0.5,
    @SerialName("max_tokens") val maxTokens: Int = 90 // Короткие голосовые ответы (до 25 слов) для молниеносной скорости
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
        history: List<Message>,
        modelOverride: String?
    ): Resource<String> = withContext(Dispatchers.IO) {
        val apiKey = securityManager.getApiKey().trim()
        if (apiKey.isEmpty() || apiKey.length < 5) {
            return@withContext Resource.Error(
                IllegalStateException("Ключ не указан"),
                "Пожалуйста, введите API-ключ в настройках."
            )
        }

        try {
            if (apiKey.startsWith("sk-or-")) {
                // OpenRouter: Llama 3.3 70B (сверхбыстрый и умный)
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
                    model = modelOverride ?: "meta-llama/llama-3.3-70b-instruct:free",
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else if (apiKey.startsWith("gsk_")) {
                // Groq: 500 токенов/сек (мгновенный отклик 150мс)
                val groqModel = if (modelOverride != null && !modelOverride.contains("/")) {
                    modelOverride
                } else {
                    "llama-3.3-70b-versatile"
                }
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                    model = groqModel,
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else if (apiKey.startsWith("sk-")) {
                // OpenAI GPT-4o Mini
                val openAiModel = if (modelOverride != null && !modelOverride.contains("/")) {
                    modelOverride
                } else {
                    "gpt-4o-mini"
                }
                return@withContext callOpenAiCompatible(
                    endpointUrl = "https://api.openai.com/v1/chat/completions",
                    model = openAiModel,
                    apiKey = apiKey,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = history
                )
            } else {
                return@withContext callDirectGemini(apiKey, prompt, systemPrompt, history, modelOverride)
            }
        } catch (e: SocketTimeoutException) {
            Resource.Error(e, "Таймаут подключения к AI. Проверьте интернет.")
        } catch (e: IOException) {
            Resource.Error(e, "Ошибка сети при связи с AI.")
        } catch (e: Exception) {
            Resource.Error(e, "Ошибка AI: ${e.localizedMessage}")
        }
    }

    private fun callDirectGemini(
        apiKey: String,
        prompt: String,
        systemPrompt: String,
        history: List<Message>,
        modelOverride: String? = null
    ): Resource<String> {
        val contents = mutableListOf<GeminiContentDto>()
        history.takeLast(4).forEach { msg ->
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

        val effectiveSystem = if (systemPrompt.isNotBlank()) {
            "$systemPrompt\nОтвечай ультра-кратко: ровно 1 предложение."
        } else {
            "Отвечай кратко, 1 предложением, живым разговорным языком."
        }

        val systemInstruction = GeminiSystemInstructionDto(parts = listOf(GeminiPartDto(text = effectiveSystem)))

        val requestBodyObj = GeminiRequestDto(contents, systemInstruction)
        val jsonBody = json.encodeToString(GeminiRequestDto.serializer(), requestBodyObj)

        val geminiModel = if (modelOverride != null && (modelOverride.startsWith("gemini-") || !modelOverride.contains("/"))) {
            modelOverride
        } else {
            "gemini-flash-latest"
        }

        val requestUrl = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent"

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
                400, 403 -> "Google заблокировал запрос (403). Включите VPN или используйте ключ OpenRouter (sk-or-...)."
                429 -> "Лимит запросов Gemini исчерпан. Подождите 30 сек."
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
        val voiceConstraint = "Ты JARVIS. Отвечай кратко и четко (1-2 предложения), без списков и markdown."
        
        val effectiveSystem = if (systemPrompt.isNotBlank()) "$systemPrompt\n$voiceConstraint" else voiceConstraint
        messages.add(OpenAiMessageDto("system", effectiveSystem))

        history.takeLast(4).forEach { msg ->
            messages.add(OpenAiMessageDto(if (msg.role == MessageRole.ASSISTANT) "assistant" else "user", msg.text))
        }
        if (messages.lastOrNull()?.content != prompt) {
            messages.add(OpenAiMessageDto("user", prompt))
        }

        val requestObj = OpenAiChatRequest(model = model, messages = messages, maxTokens = 90)
        val jsonBody = json.encodeToString(OpenAiChatRequest.serializer(), requestObj)

        val request = Request.Builder()
            .url(endpointUrl)
            .post(jsonBody.toRequestBody(mediaType))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/freelanceTM/jarvis")
            .header("X-Title", "JARVIS Voice Assistant")
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
