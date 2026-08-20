package com.jarvis.assistant.core.network

import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Upstream response exceeded the component's explicit decompressed-byte budget. */
class ResponseBodyTooLargeException(
    val maxBytes: Long
) : IOException("Response body exceeds $maxBytes bytes")

/**
 * Reads a body without the unbounded allocation performed by `ResponseBody.string()`.
 *
 * The declared Content-Length is checked first. The source itself is then
 * requested only up to limit + 1, so unknown-length and transparently gzipped
 * bodies are bounded by their bytes visible after OkHttp interceptors.
 */
fun ResponseBody.readUtf8Bounded(maxBytes: Long): String {
    require(maxBytes > 0) { "maxBytes must be positive" }

    val declaredLength = contentLength()
    if (declaredLength > maxBytes) throw ResponseBodyTooLargeException(maxBytes)

    val source = source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw ResponseBodyTooLargeException(maxBytes)

    return source.readString(StandardCharsets.UTF_8)
}
