package com.jarvis.assistant.core.license

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат серверной валидации кода активации.
 */
sealed interface ServerValidationResult {
    /** Код подтверждён сервером. */
    data class Valid(val licenseDays: Int = 30) : ServerValidationResult

    /** Сервер отклонил код (причина — для пользователя). */
    data class Invalid(val reason: String) : ServerValidationResult

    /** Сервер недоступен (офлайн / не развёрнут / таймаут). */
    data object ServiceUnavailable : ServerValidationResult
}

/**
 * Серверная валидация кодов — источник правды (пункт аудита #2).
 *
 * Финальная проверка валидности кода и контрольной суммы выполняется на
 * сервере: клиент НЕ должен содержать соль и алгоритм генерации/проверки.
 */
interface LicenseServerValidator {
    suspend fun validate(code: String, hardwareId: String): ServerValidationResult
}

@Serializable
private data class ValidateRequest(
    @SerialName("code") val code: String,
    @SerialName("hardware_id") val hardwareId: String
)

@Serializable
private data class ValidateResponse(
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("reason") val reason: String? = null,
    @SerialName("license_days") val licenseDays: Int = 30
)

/**
 * HTTP-реализация серверной валидации.
 *
 * TODO(server): endpoint /v1/license/validate ещё не существует — URL ниже
 * является заглушкой. Пока сервер не развёрнут, validate() возвращает
 * ServiceUnavailable, и код уходит во ВРЕМЕННЫЙ локальный fallback
 * ([LocalChecksumVerifier]), помеченный для удаления.
 *
 * Когда endpoint появится, сервер также должен проверять:
 *  - одноразовость кода (cross-device);
 *  - привязку к hardware ID;
 *  - отзыв кодов без обновления APK.
 */
@Singleton
class HttpLicenseServerValidator @Inject constructor() : LicenseServerValidator {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // TODO(server): заменить на реальный endpoint валидации лицензий.
    private val validateUrl = "https://api.jarvis.ai/v1/license/validate"

    override suspend fun validate(code: String, hardwareId: String): ServerValidationResult =
        withContext(Dispatchers.IO) {
            try {
                val body = json.encodeToString(
                    ValidateRequest.serializer(),
                    ValidateRequest(code = code, hardwareId = hardwareId)
                )
                val request = Request.Builder()
                    .url(validateUrl)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext ServerValidationResult.ServiceUnavailable
                    val respBody = response.body?.string()
                        ?: return@withContext ServerValidationResult.ServiceUnavailable
                    val parsed = json.decodeFromString(ValidateResponse.serializer(), respBody)
                    if (parsed.valid) {
                        ServerValidationResult.Valid(parsed.licenseDays)
                    } else {
                        ServerValidationResult.Invalid(parsed.reason ?: "Код отклонён сервером")
                    }
                }
            } catch (_: Exception) {
                // Сервер недоступен (офлайн / не развёрнут / таймаут).
                ServerValidationResult.ServiceUnavailable
            }
        }
}
