package com.jarvis.server

import com.jarvis.server.provider.OkHttpTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpTransportCancellationTest {
    @Test
    fun `coroutine cancellation cancels a real in-flight OkHttp call`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            requestStarted.countDown()
            releaseServer.await(10, TimeUnit.SECONDS)
            runCatching {
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            val transport = OkHttpTransport()
            val job = launch(Dispatchers.IO) {
                transport.post(
                    "http://127.0.0.1:${server.address.port}/slow",
                    emptyMap(),
                    "{}",
                    connectTimeoutMs = 2_000,
                    requestTimeoutMs = 30_000
                )
            }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(job.isCancelled)
            assertTrue("cancellation took ${elapsedMs}ms", elapsedMs < 2_000)
        } finally {
            releaseServer.countDown()
            delay(50)
            server.stop(0)
        }
    }
}
