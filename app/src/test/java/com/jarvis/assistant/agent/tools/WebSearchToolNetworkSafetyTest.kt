package com.jarvis.assistant.agent.tools

import com.jarvis.assistant.agent.tools.intelligence.WebSearchTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebSearchToolNetworkSafetyTest {

    @Test
    fun `oversized first provider response is rejected without fallback allocation`() = runBlocking {
        val calls = AtomicInteger()
        val interceptor = Interceptor { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("x".repeat(512 * 1024 + 1).toResponseBody("application/json".toMediaType()))
                .build()
        }
        val tool = WebSearchTool(OkHttpClient.Builder().addInterceptor(interceptor).build())

        val result = tool.execute(buildJsonObject { put("query", "безопасность") })

        assertEquals("RESPONSE_TOO_LARGE", result.error)
        assertEquals(1, calls.get())
    }
}
