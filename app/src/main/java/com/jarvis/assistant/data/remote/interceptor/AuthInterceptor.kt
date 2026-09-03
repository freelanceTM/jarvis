package com.jarvis.assistant.data.remote.interceptor

import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.data.remote.JarvisApiClient
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import javax.inject.Inject

/**
 * Добавляет токен доступа JARVIS API (Этап 3).
 *
 * ВАЖНО: токен подставляется ТОЛЬКО для запросов к собственному бэкенду.
 * Ключей AI-провайдеров на устройстве больше нет, а отправлять свой токен
 * на посторонние хосты недопустимо — поэтому строгая проверка хоста.
 *
 * `JarvisApiClient` ставит заголовок сам; интерсептор нужен для остальных
 * вызовов к api.jarvis.ai (лицензии, конфиг) и как страховка.
 */
class AuthInterceptor @Inject constructor(
    private val securityManager: SecurityManager,
    private val licenseManager: com.jarvis.assistant.core.license.LicenseManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header("Content-Type", "application/json")
            .header("User-Agent", "JARVIS-Android/0.3")

        val isJarvisBackend = BackendRequestPolicy.isTrusted(originalRequest.url)
        if (originalRequest.header("Authorization") != null && !isJarvisBackend) {
            throw IOException("Refusing to send Authorization outside the configured JARVIS origin")
        }
        if (isJarvisBackend && originalRequest.header("Authorization") == null) {
            val token = securityManager.getAccessToken().trim()
            if (token.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            // V007: enforcement-путь сервера требует устройство — jrv_-токен
            // сверяется с привязкой по X-Jarvis-Device. Тот же ID, что в
            // redeem/validate (сервер хранит хеш и решает сам).
            val deviceId = licenseManager.getDeviceId()
            if (deviceId.isNotBlank()) {
                requestBuilder.header("X-Jarvis-Device", deviceId)
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}

internal object BackendRequestPolicy {
    private val backend: HttpUrl = JarvisApiClient.BASE_URL.toHttpUrl()

    fun isTrusted(url: HttpUrl): Boolean {
        val secureScheme = backend.scheme == "https" ||
            (BuildConfig.ALLOW_CLEARTEXT_BACKEND && backend.scheme == "http")
        return secureScheme && url.scheme == backend.scheme &&
            url.host == backend.host && url.port == backend.port
    }
}
