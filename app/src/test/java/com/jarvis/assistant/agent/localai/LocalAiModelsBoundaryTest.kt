package com.jarvis.assistant.agent.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalAiModelsBoundaryTest {

    @Test
    fun `generation config accepts boundaries and rejects non-finite or out of range values`() {
        GenerationConfig(maxTokens = 1, temperature = 0f, topP = 0f, topK = 1)
        GenerationConfig(maxTokens = 2048, temperature = 2f, topP = 1f, topK = 1_000)

        val invalid = listOf<() -> Unit>(
            { GenerationConfig(maxTokens = 0) },
            { GenerationConfig(maxTokens = 2049) },
            { GenerationConfig(temperature = Float.NaN) },
            { GenerationConfig(temperature = Float.POSITIVE_INFINITY) },
            { GenerationConfig(temperature = 2.01f) },
            { GenerationConfig(topP = -0.01f) },
            { GenerationConfig(topP = 1.01f) },
            { GenerationConfig(topK = 0) },
            { GenerationConfig(topK = 1_001) }
        )
        invalid.forEach { factory ->
            var thrown = false
            try {
                factory()
            } catch (_: IllegalArgumentException) {
                thrown = true
            }
            assertTrue(thrown)
        }
    }

    @Test
    fun `metrics log formatting is locale independent and contains no payload`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val rendered = InferenceMetrics(
                promptChars = 10,
                responseChars = 20,
                latencyMs = 30,
                approxTokensPerSecond = 1.5f
            ).toLogString()
            assertTrue(rendered.contains("~tok/s=1.5"))
            assertTrue(rendered.contains("promptChars=10"))
            assertEquals(false, rendered.contains("1,5"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
