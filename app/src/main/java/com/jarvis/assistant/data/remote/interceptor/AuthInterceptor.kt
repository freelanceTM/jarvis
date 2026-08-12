package com.jarvis.assistant.data.remote.interceptor

import com.jarvis.assistant.core.security.SecurityManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val securityManager: SecurityManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val apiKey = securityManager.getApiKey().trim()
        val url = originalRequest.url.toString()

        val requestBuilder = originalRequest.newBuilder()
            .header("Content-Type", "application/json")
            .header("User-Agent", "JARVIS-Android/0.2")

        // Добавляем Authorization Bearer только для OpenAI/OpenRouter/Groq, исключая Google Gemini (использует x-goog-api-key)
        if (apiKey.isNotEmpty() && !url.contains("googleapis.com") && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return chain.proceed(requestBuilder.build())
    }
}
