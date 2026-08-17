package com.jarvis.assistant.core.license

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Удалённый конфиг лицензий — источник правды для мастер-кодов.
 *
 * Мастер-коды активации БОЛЬШЕ не зашиты в APK: они приходят сюда и могут
 * быть отозваны (или полностью отключены) БЕЗ обновления приложения.
 *
 * TODO(server): endpoint /v1/license-config ещё не существует — URL ниже
 * является заглушкой. Пока сервер не развёрнут, fetch() возвращает null и
 * мастер-коды отключены (безопасное поведение по умолчанию). Когда endpoint
 * появится, нужно также перенести на сервер: одноразовость кода (cross-device)
 * и привязку к hardware ID (см. TODO в LicenseManagerImpl).
 */
interface LicenseRemoteConfig {
    /**
     * @return конфигурация лицензий или null, если источник недоступен
     *         (нет сети / сервер не развёрнут / ошибка).
     */
    suspend fun fetch(): LicenseConfigData?
}

/** Конфигурация лицензий с удалённого источника. */
@Serializable
data class LicenseConfigData(
    /** Мастер-коды, разрешённые сервером (могут быть отозваны в любой момент). */
    @SerialName("master_codes")
    val masterCodes: List<String> = emptyList(),

    /** Глобальный рубильник мастер-кодов: false — все мастер-коды отключены. */
    @SerialName("master_codes_enabled")
    val masterCodesEnabled: Boolean = false,

    /** Версия конфига для кеширования/диагностики. */
    @SerialName("config_version")
    val configVersion: Int = 0
)

/**
 * HTTP-реализация удалённого конфига лицензий.
 *
 * Использует отдельный OkHttpClient с короткими таймаутами, чтобы сбой
 * endpoint'а не блокировал активацию надолго.
 */
@Singleton
class HttpLicenseRemoteConfig @Inject constructor() : LicenseRemoteConfig {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // TODO(server): заменить на реальный endpoint конфигурации лицензий.
    // Пока сервера нет — запрос всегда падает, fetch() возвращает null,
    // и мастер-коды отключены.
    private val configUrl = "https://api.jarvis.ai/v1/license-config"

    override suspend fun fetch(): LicenseConfigData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(configUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString(LicenseConfigData.serializer(), body)
            }
        } catch (_: Exception) {
            // Источник недоступен (офлайн / сервер не развёрнут / таймаут) —
            // безопасное поведение: мастер-коды не работают.
            null
        }
    }
}
