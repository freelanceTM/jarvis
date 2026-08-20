package com.jarvis.server.provider

import com.jarvis.server.config.ProviderConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP-транспорт, отделённый от провайдеров.
 *
 * Благодаря этому провайдеров можно тестировать без сети: в тестах
 * подставляется fake-транспорт (пункт 34 ТЗ).
 */
interface HttpTransport {
    /**
     * @return [HttpTransportResponse] или бросает [TransportException].
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long
    ): HttpTransportResponse
}

data class HttpTransportResponse(val status: Int, val body: String)

/** Транспортный сбой — сеть/таймаут, до получения HTTP-статуса. */
class TransportException(
    val kind: ProviderFailureKind,
    val detail: String
) : Exception(detail)

// =============================================================== DTO (OpenAI-совместимые)

@Serializable
private data class OaiMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
private data class OaiRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<OaiMessage>,
    @SerialName("temperature") val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int
)

@Serializable
private data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null
)

@Serializable
private data class OaiChoice(@SerialName("message") val message: OaiMessage? = null)

@Serializable
private data class OaiResponse(
    @SerialName("choices") val choices: List<OaiChoice> = emptyList(),
    @SerialName("usage") val usage: OaiUsage? = null
)

// =============================================================== DTO (Gemini)

@Serializable
private data class GemPart(@SerialName("text") val text: String)

@Serializable
private data class GemContent(
    @SerialName("role") val role: String? = null,
    @SerialName("parts") val parts: List<GemPart> = emptyList()
)

@Serializable
private data class GemSystemInstruction(@SerialName("parts") val parts: List<GemPart>)

@Serializable
private data class GemGenerationConfig(
    @SerialName("temperature") val temperature: Double,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int
)

@Serializable
private data class GemRequest(
    @SerialName("contents") val contents: List<GemContent>,
    @SerialName("systemInstruction") val systemInstruction: GemSystemInstruction? = null,
    @SerialName("generationConfig") val generationConfig: GemGenerationConfig
)

@Serializable
private data class GemCandidate(@SerialName("content") val content: GemContent? = null)

@Serializable
private data class GemUsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Long? = null,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Long? = null,
    @SerialName("totalTokenCount") val totalTokenCount: Long? = null
)

@Serializable
private data class GemResponse(
    @SerialName("candidates") val candidates: List<GemCandidate> = emptyList(),
    @SerialName("usageMetadata") val usageMetadata: GemUsageMetadata? = null
)

// =============================================================== Общая часть

/**
 * Общая логика HTTP-провайдеров: выполнение запроса и классификация ошибок.
 *
 * Orchestration (fallback, health, retry) здесь НЕТ — это зона
 * [ProviderManager] (пункт «не дублируй orchestration logic в providers»).
 */
abstract class BaseHttpProvider(
    protected val config: ProviderConfig,
    protected val transport: HttpTransport,
    protected val json: Json
) : AiProvider {

    override fun isConfigured(): Boolean = config.enabled && config.hasKey

    override suspend fun execute(request: ProviderRequest): ProviderResult {
        if (!isConfigured()) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.NOT_CONFIGURED,
                detail = "provider ${id.name} is disabled or has no API key"
            )
        }

        return try {
            val response = transport.post(
                url = endpointUrl(),
                headers = headers(),
                body = buildBody(request),
                connectTimeoutMs = config.connectTimeoutMs,
                requestTimeoutMs = config.requestTimeoutMs
            )

            if (response.status !in 200..299) {
                return ProviderResult.Failure(
                    kind = classifyStatus(response.status),
                    // detail только для логов сервера, клиенту не уходит
                    detail = "HTTP ${response.status}",
                    httpStatus = response.status
                )
            }

            parseSuccess(response.body)
        } catch (e: TransportException) {
            ProviderResult.Failure(kind = e.kind, detail = e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ProviderResult.Failure(
                kind = ProviderFailureKind.UNKNOWN,
                detail = e.javaClass.simpleName
            )
        }
    }

    protected abstract fun endpointUrl(): String
    protected abstract fun headers(): Map<String, String>
    protected abstract fun buildBody(request: ProviderRequest): String
    protected abstract fun parseSuccess(body: String): ProviderResult

    /** Единая классификация HTTP-статусов (пункт 22 ТЗ). */
    protected fun classifyStatus(status: Int): ProviderFailureKind = when (status) {
        401, 403 -> ProviderFailureKind.AUTH
        408 -> ProviderFailureKind.TIMEOUT
        429 -> ProviderFailureKind.RATE_LIMITED
        400, 404, 422 -> ProviderFailureKind.BAD_REQUEST
        in 500..599 -> ProviderFailureKind.SERVER_ERROR
        else -> ProviderFailureKind.UNKNOWN
    }

    protected fun parseOpenAiCompatible(body: String, model: String): ProviderResult {
        val parsed = json.decodeFromString(OaiResponse.serializer(), body)
        val text = parsed.choices.firstOrNull()?.message?.content?.trim()

        if (text.isNullOrEmpty()) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.UNKNOWN,
                detail = "empty completion"
            )
        }

        return ProviderResult.Success(
            text = text,
            model = model,
            inputTokens = parsed.usage?.promptTokens,
            outputTokens = parsed.usage?.completionTokens,
            totalTokens = parsed.usage?.totalTokens
        )
    }

    protected fun buildOpenAiCompatibleBody(request: ProviderRequest, model: String): String =
        json.encodeToString(
            OaiRequest.serializer(),
            OaiRequest(
                model = model,
                messages = listOf(
                    OaiMessage("system", request.systemPrompt),
                    OaiMessage("user", request.prompt)
                ),
                temperature = request.temperature,
                maxTokens = request.maxTokens
            )
        )
}

// =============================================================== Groq

/** Groq: OpenAI-совместимый API. Веб-доступа нет. */
class GroqProvider(
    config: ProviderConfig,
    transport: HttpTransport,
    json: Json
) : BaseHttpProvider(config, transport, json) {

    override val id = ProviderId.GROQ

    override val capabilities = ProviderCapabilities(
        supportsChat = true,
        supportsWeb = false,
        supportsStreaming = false,
        supportsToolCalling = true
    )

    override fun endpointUrl() = config.baseUrl

    override fun headers() = mapOf(
        "Authorization" to "Bearer ${config.apiKey}",
        "Content-Type" to "application/json"
    )

    override fun buildBody(request: ProviderRequest) =
        buildOpenAiCompatibleBody(request, config.model)

    override fun parseSuccess(body: String) = parseOpenAiCompatible(body, config.model)
}

// =============================================================== OpenRouter

/** OpenRouter: тоже OpenAI-совместимый, но со своими заголовками атрибуции. */
class OpenRouterProvider(
    config: ProviderConfig,
    transport: HttpTransport,
    json: Json
) : BaseHttpProvider(config, transport, json) {

    override val id = ProviderId.OPENROUTER

    override val capabilities = ProviderCapabilities(
        supportsChat = true,
        supportsWeb = false,
        supportsStreaming = false,
        supportsToolCalling = true
    )

    override fun endpointUrl() = config.baseUrl

    override fun headers() = mapOf(
        "Authorization" to "Bearer ${config.apiKey}",
        "Content-Type" to "application/json",
        "HTTP-Referer" to "https://jarvis.ai",
        "X-Title" to "JARVIS"
    )

    override fun buildBody(request: ProviderRequest) =
        buildOpenAiCompatibleBody(request, config.model)

    override fun parseSuccess(body: String) = parseOpenAiCompatible(body, config.model)
}

// =============================================================== Gemini

/**
 * Google Gemini: собственный формат запроса/ответа и ключ в заголовке
 * `x-goog-api-key` (не Bearer).
 */
class GeminiProvider(
    config: ProviderConfig,
    transport: HttpTransport,
    json: Json
) : BaseHttpProvider(config, transport, json) {

    override val id = ProviderId.GEMINI

    override val capabilities = ProviderCapabilities(
        supportsChat = true,
        supportsWeb = false,
        supportsStreaming = false,
        supportsToolCalling = false
    )

    override fun endpointUrl() = "${config.baseUrl}/${config.model}:generateContent"

    override fun headers() = mapOf(
        "x-goog-api-key" to (config.apiKey ?: ""),
        "Content-Type" to "application/json"
    )

    override fun buildBody(request: ProviderRequest): String =
        json.encodeToString(
            GemRequest.serializer(),
            GemRequest(
                contents = listOf(
                    GemContent(role = "user", parts = listOf(GemPart(request.prompt)))
                ),
                systemInstruction = GemSystemInstruction(
                    parts = listOf(GemPart(request.systemPrompt))
                ),
                generationConfig = GemGenerationConfig(
                    temperature = request.temperature,
                    maxOutputTokens = request.maxTokens
                )
            )
        )

    override fun parseSuccess(body: String): ProviderResult {
        val parsed = json.decodeFromString(GemResponse.serializer(), body)
        val text = parsed.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.joinToString("") { it.text }
            ?.trim()

        if (text.isNullOrEmpty()) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.UNKNOWN,
                detail = "empty candidates"
            )
        }

        return ProviderResult.Success(
            text = text,
            model = config.model,
            inputTokens = parsed.usageMetadata?.promptTokenCount,
            outputTokens = parsed.usageMetadata?.candidatesTokenCount,
            totalTokens = parsed.usageMetadata?.totalTokenCount
        )
    }
}
