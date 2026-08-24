package com.jarvis.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PrivacyArchitectureTest {
    @Test
    fun `all production cloud entry points retain a privacy gate`() {
        val root = repositoryRoot()
        val serverFiles = kotlinFiles(root.resolve("server/src/main/kotlin"))
        val appFiles = kotlinFiles(root.resolve("app/src/main/java"))

        val providerManagerCallers = serverFiles.filter {
            Files.readString(it).contains("providerManager.execute(")
        }.map { root.relativize(it).toString() }
        assertEquals(
            listOf("server/src/main/kotlin/com/jarvis/server/router/AiRouter.kt"),
            providerManagerCallers
        )

        val repositoryCloudCallers = appFiles.filter {
            Files.readString(it).contains("aiRepository.generateResponse(")
        }.map { root.relativize(it).toString() }.sorted()
        assertEquals(
            listOf(
                "app/src/main/java/com/jarvis/assistant/agent/decision/ExecutionAdapters.kt",
                "app/src/main/java/com/jarvis/assistant/agent/translator/LiveTranslatorEngine.kt"
            ),
            repositoryCloudCallers
        )

        val production = (serverFiles + appFiles).joinToString("\n") { Files.readString(it) }
        assertFalse(production.contains("privacyLevel = \"NORMAL\""))
        assertTrue(
            Files.readString(root.resolve("server/src/main/kotlin/com/jarvis/server/api/Dto.kt"))
                .contains("ApiPrivacyLevel = ApiPrivacyLevel.UNKNOWN")
        )
        assertTrue(
            Files.readString(root.resolve("app/src/main/java/com/jarvis/assistant/domain/usecases/SendPromptUseCase.kt"))
                .contains("privacyLevel: PrivacyLevel = PrivacyLevel.UNKNOWN")
        )
        val toolExecutor = Files.readString(
            root.resolve("app/src/main/java/com/jarvis/assistant/agent/executor/ToolExecutor.kt")
        )
        assertTrue(toolExecutor.contains("privacyBlockForExternalTool"))
        assertTrue(toolExecutor.contains("tool.externalPrivacyContext(call.arguments)"))

        for (relative in listOf(
            "app/src/main/java/com/jarvis/assistant/agent/tools/accessibility/UiTypeTextTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/communication/CallTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/communication/ShareTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/communication/TelegramTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/device/OpenAppTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/productivity/AlarmTimerTool.kt",
            "app/src/main/java/com/jarvis/assistant/agent/tools/productivity/CalendarTool.kt"
        )) {
            assertTrue(relative, Files.readString(root.resolve(relative))
                .contains("mayDiscloseUserContentExternally: Boolean = true"))
        }
        assertTrue(
            Files.readString(root.resolve(
                "app/src/main/java/com/jarvis/assistant/agent/tools/intelligence/WeatherTool.kt"
            )).contains("externalPrivacyContext")
        )
    }

    private fun kotlinFiles(path: Path): List<Path> = Files.walk(path).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .sorted()
            .toList()
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
