package com.jarvis.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PrivacyLoggingRegressionTest {
    @Test
    fun `confirmed Android plaintext logging bypasses remain removed`() {
        val root = repositoryRoot()
        val voice = read(root, "app/src/main/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt")
        val loop = read(root, "app/src/main/java/com/jarvis/assistant/agent/engine/AgentCognitiveLoop.kt")
        val accessibility = read(root, "app/src/main/java/com/jarvis/assistant/agent/tools/accessibility/JarvisAccessibilityService.kt")
        val automation = read(root, "app/src/main/java/com/jarvis/assistant/agent/automation/engine/PersonalAutomationEngine.kt")
        val localAdapter = read(root, "app/src/main/java/com/jarvis/assistant/agent/decision/LocalAiExecutorAdapter.kt")
        val translator = read(root, "app/src/main/java/com/jarvis/assistant/agent/translator/LiveTranslatorEngine.kt")

        assertFalse(voice.contains("duplicate skipped: $" + "clean"))
        assertTrue(voice.contains("duplicate skipped | chars="))

        for (forbidden in listOf(
            "step=${'$'}{step.description}",
            "expected='${'$'}expected'",
            "screen='${'$'}screenContent'",
            "observation=${'$'}{observation.summary}",
            "explanation=${'$'}{replan.explanation}"
        )) assertFalse(forbidden, loop.contains(forbidden))
        assertTrue(loop.contains("screenChars="))

        assertFalse(accessibility.contains("target='${'$'}targetText'"))
        assertTrue(accessibility.contains("targetChars="))

        assertFalse(automation.contains("Rule '${'$'}{rule.name}'"))
        assertFalse(automation.contains("${'$'}{failed?.error"))
        assertFalse(localAdapter.contains("local layer error: ${'$'}{result.message}"))
        assertFalse(translator.contains("failed: ${'$'}result"))
    }

    @Test
    fun `server structured logs and usage never include prompt plaintext`() {
        val root = repositoryRoot()
        val router = read(root, "server/src/main/kotlin/com/jarvis/server/router/AiRouter.kt")
        val provider = read(root, "server/src/main/kotlin/com/jarvis/server/provider/ProviderManager.kt")

        assertFalse(router.contains("logger.info(request.text"))
        assertFalse(router.contains("logger.warn(request.text"))
        assertFalse(router.contains("\"prompt\" to request.text"))
        assertTrue(router.contains("LogSanitizer.describeText(request.text)"))
        assertFalse(provider.contains("request.prompt" + ".take"))
    }

    private fun read(root: Path, relative: String): String = Files.readString(root.resolve(relative))

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
