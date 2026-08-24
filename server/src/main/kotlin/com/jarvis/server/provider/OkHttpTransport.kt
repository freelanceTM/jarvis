package com.jarvis.server.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider HTTP transport with bounded bodies, per-call limits and real cancellation. */
class OkHttpTransport(
    private val baseClient: OkHttpClient = OkHttpClient()
) : HttpTransport {

    companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long
    ): HttpTransportResponse {
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val call = client.newCall(requestBuilder.build())

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val kind = when {
                        e is SocketTimeoutException -> ProviderFailureKind.TIMEOUT
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            ProviderFailureKind.TIMEOUT
                        else -> ProviderFailureKind.CONNECTION
                    }
                    continuation.resumeWithException(
                        TransportException(kind, e.javaClass.simpleName)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use { value -> continuation.resume(readBounded(value)) }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private fun readBounded(response: Response): HttpTransportResponse {
        val responseBody = response.body
        val declaredLength = responseBody?.contentLength() ?: 0L
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw TransportException(ProviderFailureKind.UNKNOWN, "provider response too large")
        }
        val bytes = responseBody?.byteStream()?.readNBytes(MAX_RESPONSE_BYTES + 1) ?: ByteArray(0)
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw TransportException(ProviderFailureKind.UNKNOWN, "provider response too large")
        }
        val charset = responseBody?.contentType()?.charset(StandardCharsets.UTF_8)
            ?: StandardCharsets.UTF_8
        return HttpTransportResponse(response.code, String(bytes, charset))
    }
}
