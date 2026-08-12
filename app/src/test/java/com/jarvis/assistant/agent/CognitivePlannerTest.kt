package com.jarvis.assistant.agent

import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.planner.CognitivePlanner
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CognitivePlanner
 */
class CognitivePlannerTest {
    
    private lateinit var planner: CognitivePlanner
    
    @Before
    fun setup() {
        val json = Json { ignoreUnknownKeys = true }
        val parser = ToolCallParser(json)
        planner = CognitivePlanner(parser)
    }
    
    // ===========================================
    // Predefined Scenarios
    // ===========================================
    
    @Test
    fun `leaving home scenario creates multi-step plan`() {
        val plan = planner.planForGoal("я ухожу")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.size >= 2)
        assertEquals("Подготовка телефона к выходу из дома", plan.goal)
    }
    
    @Test
    fun `coming home scenario creates plan`() {
        val plan = planner.planForGoal("я пришел домой")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.isNotEmpty())
    }
    
    @Test
    fun `sleep mode scenario creates plan`() {
        val plan = planner.planForGoal("спокойной ночи")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.isNotEmpty())
        // Should include DND
        assertTrue(plan.steps.any { it.toolCall.toolId == "device.dnd" })
    }
    
    @Test
    fun `morning mode scenario creates plan`() {
        val plan = planner.planForGoal("доброе утро")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.isNotEmpty())
    }
    
    @Test
    fun `meeting mode scenario mutes phone`() {
        val plan = planner.planForGoal("я на совещании")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.any { 
            it.toolCall.toolId == "device.volume" || it.toolCall.toolId == "device.dnd"
        })
    }
    
    @Test
    fun `driving mode scenario sets max volume`() {
        val plan = planner.planForGoal("еду на машине")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.any { it.toolCall.toolId == "device.volume" })
    }
    
    @Test
    fun `power saving mode scenario exists`() {
        val plan = planner.planForGoal("экономь заряд")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.isNotEmpty())
    }
    
    @Test
    fun `system diagnostics scenario checks multiple systems`() {
        val plan = planner.planForGoal("статус системы")
        
        assertNotNull(plan)
        assertTrue(plan!!.steps.size >= 3)
    }
    
    // ===========================================
    // Wake Word Handling
    // ===========================================
    
    @Test
    fun `wake word is stripped from query`() {
        val plan1 = planner.planForGoal("джарвис, я ухожу")
        val plan2 = planner.planForGoal("я ухожу")
        
        assertNotNull(plan1)
        assertNotNull(plan2)
        assertEquals(plan1!!.goal, plan2!!.goal)
    }
    
    // ===========================================
    // Unknown Queries
    // ===========================================
    
    @Test
    fun `unknown query returns null without LLM output`() {
        val plan = planner.planForGoal("сделай что-то странное")
        assertNull(plan)
    }
    
    // ===========================================
    // LLM-based Planning
    // ===========================================
    
    @Test
    fun `LLM tool_calls are parsed into plan`() {
        val llmOutput = """
            {
                "tool_calls": [
                    {"tool": "device.flashlight", "arguments": {"enabled": true}},
                    {"tool": "device.volume", "arguments": {"action": "up"}}
                ]
            }
        """.trimIndent()
        
        val plan = planner.planForGoal("сделай что-то", llmOutput)
        
        assertNotNull(plan)
        assertEquals(2, plan!!.steps.size)
        assertEquals("device.flashlight", plan.steps[0].toolCall.toolId)
        assertEquals("device.volume", plan.steps[1].toolCall.toolId)
    }
    
    // ===========================================
    // Replan
    // ===========================================
    
    @Test
    fun `replan returns null after max attempts`() {
        val initialPlan = planner.planForGoal("я ухожу")!!
        val failedStep = initialPlan.steps[0]
        val observation = com.jarvis.assistant.agent.planner.StepObservation.StepFailed(
            failedStep, "Test error"
        )
        
        // Attempt 3 should return null
        val newPlan = planner.replan(initialPlan, failedStep, observation, attemptNumber = 3)
        assertNull(newPlan)
    }
}
