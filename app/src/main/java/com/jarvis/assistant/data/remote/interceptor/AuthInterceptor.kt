package com.jarvis.assistant.data.remote.interceptor

import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.data.remote.JarvisApiClient
import okhttp3.Interceptor
import okhttp3.Response
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
    private val securityManager: SecurityManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header("Content-Type", "application/json")
            .header("User-Agent", "JARVIS-Android/0.3")

        val isJarvisBackend = originalRequest.url.host ==
            JarvisApiClient.BASE_URL.toHttpHostOrNull()

        if (isJarvisBackend && originalRequest.header("Authorization") == null) {
            val token = securityManager.getAccessToken().trim()
            if (token.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(requestBuilder.build())
    }

    private fun String.toHttpHostOrNull(): String? =
        substringAfter("://", "").substringBefore("/").takeIf { it.isNotEmpty() }
}
