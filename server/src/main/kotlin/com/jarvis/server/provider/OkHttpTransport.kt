package com.jarvis.server.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * OkHttp-транспорт для провайдеров.
 *
 * Каждый вызов получает СВОИ таймауты (пункт 13 ТЗ): у провайдеров они разные,
 * поэтому клиент клонируется через `newBuilder()` — это дёшево, пул соединений
 * и диспетчер переиспользуются.
 *
 * Гарантия: ни один запрос не висит дольше `requestTimeoutMs`.
 */
class OkHttpTransport(
    private val baseClient: OkHttpClient = OkHttpClient()
) : HttpTransport {

    companion object {
        /** Защита от memory DoS со стороны ошибочного/скомпрометированного upstream. */
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long
    ): HttpTransportResponse = withContext(Dispatchers.IO) {
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            // Жёсткий потолок на весь вызов, включая редиректы и повторы.
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))

        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body
                val declaredLength = responseBody?.contentLength() ?: 0L
                if (declaredLength > MAX_RESPONSE_BYTES) {
                    throw TransportException(
                        ProviderFailureKind.UNKNOWN,
                        "provider response too large"
                    )
                }

                val bytes = responseBody?.byteStream()
                    ?.readNBytes(MAX_RESPONSE_BYTES + 1)
                    ?: ByteArray(0)
                if (bytes.size > MAX_RESPONSE_BYTES) {
                    throw TransportException(
                        ProviderFailureKind.UNKNOWN,
                        "provider response too large"
                    )
                }
                val charset = responseBody?.contentType()?.charset(StandardCharsets.UTF_8)
                    ?: StandardCharsets.UTF_8

                HttpTransportResponse(
                    status = response.code,
                    body = String(bytes, charset)
                )
            }
        } catch (e: SocketTimeoutException) {
            throw TransportException(ProviderFailureKind.TIMEOUT, "socket timeout")
        } catch (e: InterruptedException) {
            throw TransportException(ProviderFailureKind.TIMEOUT, "interrupted")
        } catch (e: IOException) {
            // OkHttp сигнализирует истечение callTimeout через IOException.
            val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true
            throw TransportException(
                if (isTimeout) ProviderFailureKind.TIMEOUT else ProviderFailureKind.CONNECTION,
                e.javaClass.simpleName
            )
        }
    }
}
