package com.jarvis.server.http

import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets

class UpstreamBodyTooLargeException(maxBytes: Long) : IOException("Upstream response exceeds $maxBytes bytes")

fun ResponseBody.readUtf8Bounded(maxBytes: Long): String {
    require(maxBytes in 1 until Long.MAX_VALUE)
    if (contentLength() > maxBytes) throw UpstreamBodyTooLargeException(maxBytes)
    val source = source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw UpstreamBodyTooLargeException(maxBytes)
    return source.readString(StandardCharsets.UTF_8)
}
