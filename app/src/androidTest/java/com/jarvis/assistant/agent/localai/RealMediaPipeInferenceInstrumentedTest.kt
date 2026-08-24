package com.jarvis.assistant.agent.localai

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.agent.localai.mediapipe.MediaPipeModelManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RealModelTestEntryPoint {
    fun localModelManager(): LocalModelManager
}

@RunWith(AndroidJUnit4::class)
class RealMediaPipeInferenceInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun realModelLoadInferenceConcurrencyCancellationReloadAndCleanup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val required = args.getString("requireRealModel") == "true"
        val expectedSha = args.getString("modelExpectedSha256").orEmpty().lowercase()
        val expectedFile = java.io.File(
            java.io.File(context.filesDir, "llm"),
            LocalModelSpec.GEMMA3_1B_IT_INT4.fileName
        )
        if (!expectedFile.exists()) {
            if (required) throw AssertionError("Required real model is absent at $expectedFile")
            assumeTrue("Real model not installed; manual/device job required", false)
        }

        val manager = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RealModelTestEntryPoint::class.java
        ).localModelManager()
        val concrete = manager as MediaPipeModelManager
        assertTrue("model manager path differs from the gated path", concrete.modelFile == expectedFile)
        assertTrue("model file is unexpectedly small", concrete.modelFile.length() > 500L * 1024 * 1024)
        if (expectedSha.isNotBlank()) {
            assertTrue("model SHA-256 mismatch", sha256(concrete.modelFile) == expectedSha)
        } else if (required) {
            throw AssertionError("modelExpectedSha256 instrumentation argument is required")
        }

        manager.unload()
        val initJob = launch { manager.initialize() }
        delay(10)
        initJob.cancelAndJoin()
        assertFalse("cancelled initialization published a live runtime", manager.isReady())

        val beforePssKb = Debug.getPss()
        assertTrue(manager.initialize() is LocalModelState.Ready)
        val runtime = requireNotNull(manager.runtimeOrNull())

        val config = GenerationConfig(maxTokens = 64, temperature = 0.1f)
        val first = runtime.generate("Reply with exactly: JARVIS_OK", config)
        assertTrue(first.text.isNotBlank())
        assertTrue(first.metrics.latencyMs >= 0)

        val parallel = coroutineScope {
            listOf(
                async { runtime.generate("Return the number two.", config) },
                async { runtime.generate("Return the number three.", config) }
            ).map { it.await() }
        }
        assertTrue(parallel.all { it.text.isNotBlank() })

        lateinit var cancellationJob: kotlinx.coroutines.Job
        cancellationJob = launch {
            runtime.generate(
                "Write a long detailed essay about astronomy.",
                config.copy(maxTokens = 512)
            ) {
                cancellationJob.cancel()
            }
        }
        cancellationJob.join()
        assertTrue("inference cancellation did not cancel the coroutine", cancellationJob.isCancelled)

        val afterCancel = runtime.generate("Reply with OK.", config)
        assertTrue(afterCancel.text.isNotBlank())
        val loadedPssKb = Debug.getPss()
        assertTrue("PSS measurement must be available", beforePssKb >= 0 && loadedPssKb > 0)

        manager.unload()
        assertFalse(manager.isReady())
        Runtime.getRuntime().gc()
        delay(2_000)
        val unloadedPssKb = Debug.getPss()
        assertTrue(
            "native PSS did not fall after unload: loaded=$loadedPssKb unloaded=$unloadedPssKb",
            unloadedPssKb < loadedPssKb || unloadedPssKb <= beforePssKb + 128 * 1024
        )
        assertTrue(manager.initialize() is LocalModelState.Ready)
        assertTrue(requireNotNull(manager.runtimeOrNull()).generate("Reply with RELOADED.", config).text.isNotBlank())
        manager.unload()
        assertFalse(manager.isReady())
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
