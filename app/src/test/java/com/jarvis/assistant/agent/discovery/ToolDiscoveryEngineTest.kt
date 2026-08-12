package com.jarvis.assistant.agent.discovery

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.memory.vector.VectorEmbeddingEngine
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolDiscoveryEngineTest {

    private lateinit var engine: ToolDiscoveryEngine
    private lateinit var vectorEngine: VectorEmbeddingEngine
    private lateinit var mockTools: List<JarvisTool>

    class DummyTool(
        override val toolId: String,
        override val description: String,
        override val category: ToolCategory,
        override val riskLevel: ToolRisk = ToolRisk.LOW,
        override val parametersSchema: JsonObject = buildJsonObject { }
    ) : JarvisTool {
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult = ToolExecutionResult.success("ok")
    }

    @Before
    fun setUp() {
        vectorEngine = VectorEmbeddingEngine()
        engine = ToolDiscoveryEngine(vectorEngine)

        mockTools = listOf(
            DummyTool(
                toolId = "device.flashlight",
                description = "Включение и выключение фонарика вспышки подсветка лампочка",
                category = ToolCategory.DEVICE
            ),
            DummyTool(
                toolId = "communication.call",
                description = "Телефонный звонок контакт вызов набрать номер",
                category = ToolCategory.COMMUNICATION
            ),
            DummyTool(
                toolId = "communication.contacts",
                description = "Поиск контактов телефонная книга мама папа абоненты",
                category = ToolCategory.COMMUNICATION
            ),
            DummyTool(
                toolId = "system.battery",
                description = "Получение уровня заряда батареи аккумулятора процент энергии",
                category = ToolCategory.SYSTEM
            ),
            DummyTool(
                toolId = "device.volume",
                description = "Управление громкостью звука тише громче прибавить убавить",
                category = ToolCategory.DEVICE
            ),
            DummyTool(
                toolId = "accessibility.screen_reader",
                description = "Считывание текста на экране приложения экранный контент",
                category = ToolCategory.SYSTEM
            ),
            DummyTool(
                toolId = "media.control",
                description = "Управление музыкой плеер пауза следующий трек песня",
                category = ToolCategory.DEVICE
            )
        )
    }

    @Test
    fun testFlashlightDiscoveryOnSynonym() {
        val discovered = engine.discoverTools("включи лампочку", mockTools)
        assertTrue(discovered.any { it.toolId == "device.flashlight" })
    }

    @Test
    fun testCallAndContactsDiscovery() {
        val discovered = engine.discoverTools("позвони маме", mockTools)
        val toolIds = discovered.map { it.toolId }
        assertTrue(toolIds.contains("communication.call"))
    }

    @Test
    fun testBatteryDiscovery() {
        val discovered = engine.discoverTools("какой заряд", mockTools)
        assertTrue(discovered.any { it.toolId == "system.battery" })
    }

    @Test
    fun testVolumeDiscovery() {
        val discovered = engine.discoverTools("сделай потише", mockTools)
        assertTrue(discovered.any { it.toolId == "device.volume" })
    }

    @Test
    fun testScreenReaderDiscovery() {
        val discovered = engine.discoverTools("что на экране", mockTools)
        assertTrue(discovered.any { it.toolId == "accessibility.screen_reader" })
    }

    @Test
    fun testFuzzySimilarityCalculation() {
        val similarity = engine.calculateFuzzySimilarity("фонарик", "фанарик")
        assertTrue(similarity >= 0.80f)
    }

    @Test
    fun testPureConversationReturnsEmptyOrHighThreshold() {
        val discovered = engine.discoverTools("почему трава зеленая", mockTools)
        assertTrue(discovered.isEmpty())
    }
}
