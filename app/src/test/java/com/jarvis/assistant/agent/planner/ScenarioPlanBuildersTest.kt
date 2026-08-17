package com.jarvis.assistant.agent.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт аудита #16 (LOW): god-функция planForGoal разбита на билдеры.
 *
 * Каждый сценарий строит непустой план с ожидаемой целью; диспетчеризация
 * через ScenarioPlanBuilders.builderFor покрывает все 10 сценариев.
 */
class ScenarioPlanBuildersTest {

    @Test
    fun `all ten builders are registered`() {
        assertEquals(10, ScenarioPlanBuilders.all.size)
        // Каждый ScenarioId имеет ровно один билдер.
        assertEquals(10, ScenarioPlanBuilders.all.map { it.scenarioId }.distinct().size)
    }

    @Test
    fun `builderFor resolves each scenario`() {
        ScenarioId.values().forEach { id ->
            assertNotNull("Нет билдера для $id", ScenarioPlanBuilders.builderFor(id))
        }
    }

    @Test
    fun `leaving home builder produces plan with flashlight off`() {
        val plan = ScenarioPlanBuilders.builderFor(ScenarioId.LEAVING_HOME)!!.build("я ухожу")

        assertTrue(plan.steps.isNotEmpty())
        assertEquals("Подготовка телефона к выходу из дома", plan.goal)
        assertTrue(plan.steps.any { it.toolCall.toolId == "device.flashlight" })
    }

    @Test
    fun `sleep builder produces plan with dnd on`() {
        val plan = ScenarioPlanBuilders.builderFor(ScenarioId.SLEEP)!!.build("спокойной ночи")

        assertTrue(plan.steps.isNotEmpty())
        assertEquals("Активация ночного режима", plan.goal)
        assertTrue(plan.steps.any { it.toolCall.toolId == "device.dnd" })
    }

    @Test
    fun `reset builder produces plan with dnd off`() {
        val plan = ScenarioPlanBuilders.builderFor(ScenarioId.RESET)!!.build("отмени всё")

        assertTrue(plan.steps.isNotEmpty())
        assertEquals("Возврат к обычным настройкам", plan.goal)
        val dndStep = plan.steps.first { it.toolCall.toolId == "device.dnd" }
        assertTrue(dndStep.toolCall.arguments.toString().contains("false"))
    }

    @Test
    fun `all builders return non-empty plans`() {
        ScenarioPlanBuilders.all.forEach { builder ->
            val plan = builder.build("тест")
            assertTrue("Билдер ${builder.scenarioId} должен строить непустой план", plan.steps.isNotEmpty())
        }
    }
}
