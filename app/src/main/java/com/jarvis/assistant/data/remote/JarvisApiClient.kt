package com.jarvis.assistant.data.remote

import android.util.Log
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Клиент JARVIS API (Этап 3).
 *
 * ```
 * Android CloudAi → JarvisApiClient → JARVIS API → AI Router → Provider Manager
 * ```
 *
 * ПРИНЦИПИАЛЬНО: здесь НЕТ ключей Groq/Gemini/OpenRouter и нет знания о том,
 * какой провайдер выполнит запрос. Клиент отправляет запрос в один эндпоинт
 * и получает нормализованный ответ. Ключи провайдеров живут только на сервере.
 *
 * Наружу отдаётся существующий [Resource], поэтому вызывающий код
 * (AIRepository → ExecutionDecisionEngine) не меняется.
 */

@Serializable
data class JarvisAiRequestDto(
    @SerialName("text") val text: String,
    @SerialName("source") val source: String,
    @SerialName("privacyLevel") val privacyLevel: String,
    @SerialName("requiresWeb") val requiresWeb: Boolean,
    @SerialName("requestId") val requestId: String,
    @SerialName("systemContext") val systemContext: String? = null
)

@Serializable
data class JarvisAiResponseDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("text") val text: String = "",
    @SerialName("executionType") val executionType: String = "CLOUD_AI",
    @SerialName("requestId") val requestId: String = ""
)

@Serializable
data class JarvisApiErrorDto(
    @SerialName("code") val code: String = "INTERNAL_ERROR",
    @SerialName("message") val message: String = "",
    @SerialName("requestId") val requestId: String = ""
)

@Serializable
data class JarvisApiErrorResponseDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("error") val error: JarvisApiErrorDto = JarvisApiErrorDto()
)

/** Ошибка JARVIS API с machine-readable кодом. */
class JarvisApiException(
    val code: String,
    val requestId: String,
    userMessage: String
) : Exception(userMessage)

@Singleton
class JarvisApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val securityManager: SecurityManager,
    private val json: Json
) {
    companion object {
        private const val TAG = "JarvisApiClient"

        /**
         * Базовый URL JARVIS API. Тот же хост, что уже используют
         * LicenseRemoteConfig и LicenseServerValidator.
         */
        const val BASE_URL = "https://api.jarvis.ai"
        const val EXECUTE_PATH = "/v1/ai/execute"
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * @param source        VOICE или CHAT.
     * @param privacyLevel  NORMAL / PRIVATE / SENSITIVE — сервер применит политику.
     */
    suspend fun execute(
        text: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean,
        systemContext: String? = null
    ): Resource<String> = withContext(Dispatchers.IO) {
        val token = securityManager.getAccessToken()
        if (token.isBlank()) {
            return@withContext Resource.Error(
                IllegalStateException("NO_ACCESS_TOKEN"),
                "Приложение не активировано. Введите код активации в настройках."
            )
        }

        val requestId = UUID.randomUUID().toString()

        val payload = json.encodeToString(
            JarvisAiRequestDto.serializer(),
            JarvisAiRequestDto(
                text = text,
                source = source,
                privacyLevel = privacyLevel,
                requiresWeb = requiresWeb,
                requestId = requestId,
                systemContext = systemContext?.takeIf { it.isNotBlank() }
            )
        )

        val request = Request.Builder()
            .url("$BASE_URL$EXECUTE_PATH")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(mediaType))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val parsed = json.decodeFromString(JarvisAiResponseDto.serializer(), body)
                    if (parsed.success && parsed.text.isNotBlank()) {
                        Log.d(TAG, "ai request ok | requestId=${parsed.requestId}")
                        return@use Resource.Success(parsed.text)
                    }
                    return@use Resource.Error(
                        JarvisApiException("EMPTY_RESPONSE", requestId, "Пустой ответ сервера"),
                        "Сервер вернул пустой ответ."
                    )
                }

                // Ошибка: разбираем нормализованный контракт сервера.
                val error = runCatching {
                    json.decodeFromString(JarvisApiErrorResponseDto.serializer(), body).error
                }.getOrElse {
                    JarvisApiErrorDto(code = "HTTP_${response.code}", requestId = requestId)
                }

                Log.w(TAG, "ai request failed | code=${error.code} | requestId=${error.requestId}")

                Resource.Error(
                    JarvisApiException(error.code, error.requestId, error.message),
                    userMessageFor(error.code)
                )
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "timeout | requestId=$requestId")
            Resource.Error(e, "Таймаут подключения к серверу JARVIS. Проверьте интернет.")
        } catch (e: IOException) {
            Log.w(TAG, "network error | requestId=$requestId")
            Resource.Error(e, "Ошибка сети при обращении к серверу JARVIS.")
        } catch (e: Exception) {
            Log.e(TAG, "unexpected failure | requestId=$requestId", e)
            Resource.Error(e, "Не удалось выполнить запрос.")
        }
    }

    /** Понятные пользователю сообщения по machine-readable кодам сервера. */
    private fun userMessageFor(code: String): String = when (code) {
        "UNAUTHORIZED" -> "Требуется активация. Проверьте код в настройках."
        "FORBIDDEN" -> "Операция недоступна для вашего тарифа."
        "RATE_LIMITED" -> "Слишком много запросов. Подождите немного, сэр."
        "PRIVACY_POLICY_VIOLATION" ->
            "Этот запрос помечен как приватный и не может быть обработан в облаке."
        "PROVIDER_TIMEOUT" -> "Сервер AI не ответил вовремя. Попробуйте ещё раз."
        "PROVIDER_UNAVAILABLE",
        "ALL_PROVIDERS_UNAVAILABLE" -> "Сервис AI временно недоступен. Попробуйте позже."
        "PROVIDER_ERROR" -> "Ошибка на стороне сервиса AI."
        "INVALID_REQUEST" -> "Некорректный запрос."
        "PAYLOAD_TOO_LARGE" -> "Запрос слишком длинный."
        else -> "Не удалось связаться с сервером JARVIS."
    }
}
