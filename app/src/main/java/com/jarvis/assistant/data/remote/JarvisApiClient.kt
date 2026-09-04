package com.jarvis.assistant.data.remote

import android.util.Log
import com.jarvis.assistant.core.constants.AppConstants
import com.jarvis.assistant.core.network.ResponseBodyTooLargeException
import com.jarvis.assistant.core.network.readUtf8Bounded
import com.jarvis.assistant.core.request.RequestIds
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.AccessTokenPolicy
import com.jarvis.assistant.core.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

/** Одно сообщение истории диалога (CR-03). Зеркалит server `MessageDto`. */
@Serializable
data class MessageDto(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
data class JarvisAiRequestDto(
    @SerialName("text") val text: String,
    @SerialName("source") val source: String,
    @SerialName("privacyLevel") val privacyLevel: String,
    @SerialName("requiresWeb") val requiresWeb: Boolean,
    @SerialName("cloudExplicitlyAllowed") val cloudExplicitlyAllowed: Boolean = false,
    @SerialName("requestId") val requestId: String,
    @SerialName("systemContext") val systemContext: String? = null,
    @SerialName("history") val history: List<MessageDto> = emptyList()
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
         * Базовый URL JARVIS API. Тот же хост использует LicenseServerValidator.
         */
        val BASE_URL: String = AppConstants.JARVIS_API_BASE_URL
        const val EXECUTE_PATH = "/v1/ai/execute"
        private const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        /**
         * CR-06: клиентский deadline.
         *
         * Единый контракт с сервером: серверный per-request deadline = 28 секунд
         * (SERVER_REQUEST_DEADLINE_MS в Main.kt). Клиентский callTimeout = 30
         * секунд — даёт 2 секунды запаса, чтобы сервер успел вернуть 504
         * PROVIDER_TIMEOUT с телом ошибки, а не выкинуть SocketTimeoutException
         * без внятного ответа.
         */
        const val CALL_TIMEOUT_SECONDS: Long = 30L
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val apiHttpClient = okHttpClient.newBuilder()
        // Authenticated API calls must never rely on HTTP(S) redirects.
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * @param source        VOICE или CHAT.
     * @param privacyLevel  NORMAL / PRIVATE / SENSITIVE — сервер применит политику.
     * @param history       CR-03: последние сообщения диалога (user/assistant).
     *                      Пустой список = поведение до CR-03.
     */
    suspend fun execute(
        text: String,
        source: String,
        privacyLevel: String,
        requiresWeb: Boolean,
        systemContext: String? = null,
        cloudExplicitlyAllowed: Boolean = false,
        history: List<MessageDto> = emptyList(),
        /**
         * OBSERVABILITY: сквозной request id агента (`omx_…`). Генерируется на
         * клиенте ОДИН РАЗ на пользовательский запрос; сервер пишет его в
         * ai_usage_records и возвращает эхом. Legacy-вызовы без id получают
         * свежий omx-id здесь.
         */
        requestId: String = RequestIds.newId()
    ): Resource<String> = withContext(Dispatchers.IO) {
        val token = securityManager.getAccessToken()
        if (!AccessTokenPolicy.isValid(token)) {
            return@withContext Resource.Error(
                IllegalStateException("INVALID_ACCESS_TOKEN"),
                "Токен доступа JARVIS отсутствует или имеет неверный формат."
            )
        }

        // OBSERVABILITY: раньше здесь рождался случайный UUID, не связанный
        // с агентным запросом (разрыв корреляции Voice→Server). Теперь id
        // приходит снаружи; свой генерируем только для legacy-вызовов.
        val effectiveRequestId = requestId.ifBlank { RequestIds.newId() }

        Log.i(TAG, "api request | requestId=$effectiveRequestId | source=$source")

        val payload = json.encodeToString(
            JarvisAiRequestDto.serializer(),
            JarvisAiRequestDto(
                text = text,
                source = source,
                privacyLevel = privacyLevel,
                requiresWeb = requiresWeb,
                cloudExplicitlyAllowed = cloudExplicitlyAllowed,
                requestId = effectiveRequestId,
                systemContext = systemContext?.takeIf { it.isNotBlank() },
                // CR-03: отсылаем только непустые сообщения. Роли подгоняем под
                // OpenAI-каноничный вид (user/assistant/system).
                history = history.filter { it.content.isNotBlank() }
            )
        )

        val request = Request.Builder()
            .url("$BASE_URL$EXECUTE_PATH")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            // CR-06: сообщаем серверу wall-clock deadline (epoch ms) до
            // которого мы готовы ждать ответ. Сервер использует
            // min(28s server budget, X-Request-Deadline) и делает early-out,
            // если попытка провайдера не укладывается.
            .header(
                "X-Request-Deadline",
                (System.currentTimeMillis() +
                    TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS.toLong())).toString()
            )
            .post(payload.toRequestBody(mediaType))
            .build()

        // CR-05: вместо блокирующего execute() используем асинхронный enqueue
        // с suspendCancellableCoroutine, чтобы отмена корутины реально
        // отменяла OkHttp Call (invokeOnCancellation → call.cancel()),
        // а не просто бросала ждать IO-поток в никуда.
        return@withContext try {
            suspendCancellableCoroutine<Resource<String>> { cont ->
                val call = apiHttpClient.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isActive) return
                        Log.w(TAG, "api failure | requestId=$effectiveRequestId | type=${e.javaClass.simpleName}")
                        cont.resume(exceptionToResource(e, effectiveRequestId))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!cont.isActive) {
                            response.close()
                            return
                        }
                        try {
                            response.use { resp ->
                                val body = resp.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                                Log.i(TAG, "api response | requestId=$effectiveRequestId | http=${resp.code}")
                                cont.resume(parseHttpResponse(resp, body, effectiveRequestId))
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t) else throw t
                        }
                    }
                })
            }
        } catch (e: ResponseBodyTooLargeException) {
            Log.w(TAG, "oversized response | requestId=$effectiveRequestId")
            Resource.Error(e, "Сервер JARVIS вернул слишком большой ответ.")
        } catch (e: Exception) {
            Log.e(TAG, "unexpected failure | requestId=$effectiveRequestId | type=${e.javaClass.simpleName}")
            Resource.Error(e, "Не удалось выполнить запрос.")
        }
    }

    /** CR-05: разбор ответа вынесен из enqueue-callback для читаемости. */
    private fun parseHttpResponse(response: Response, body: String, requestId: String): Resource<String> {
        if (response.isSuccessful) {
            val parsed = json.decodeFromString(JarvisAiResponseDto.serializer(), body)
            if (parsed.success && parsed.text.isNotBlank()) {
                Log.d(TAG, "ai request ok | requestId=${parsed.requestId}")
                return Resource.Success(parsed.text)
            }
            return Resource.Error(
                JarvisApiException("EMPTY_RESPONSE", requestId, "Пустой ответ сервера"),
                "Сервер вернул пустой ответ."
            )
        }
        val error = runCatching {
            json.decodeFromString(JarvisApiErrorResponseDto.serializer(), body).error
        }.getOrElse {
            JarvisApiErrorDto(code = "HTTP_${response.code}", requestId = requestId)
        }
        Log.w(TAG, "ai request failed | code=${error.code} | requestId=${error.requestId}")
        return Resource.Error(
            JarvisApiException(error.code, error.requestId, error.message),
            userMessageFor(error.code)
        )
    }

    /** CR-05: маппинг сетевых исключений на Resource.Error (вызывается из onFailure). */
    private fun exceptionToResource(e: IOException, requestId: String): Resource<String> = when (e) {
        is SocketTimeoutException -> {
            Log.w(TAG, "timeout | requestId=$requestId")
            Resource.Error(e, "Таймаут подключения к серверу JARVIS. Проверьте интернет.")
        }
        else -> {
            // Cancellation от OkHttp после call.cancel() прилетает как IOException
            // с message "Canceled" — это ожидаемое состояние, маппим в ошибку
            // отмены; вызывающий код корректно обрабатывает её как завершение.
            if (e.message?.contains("Canceled", ignoreCase = true) == true) {
                Log.d(TAG, "call cancelled | requestId=$requestId")
            } else {
                Log.w(TAG, "network error | requestId=$requestId", e)
            }
            Resource.Error(e, "Ошибка сети при обращении к серверу JARVIS.")
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
