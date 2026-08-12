package com.jarvis.assistant.agent.router

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskRouterTest {

    private lateinit var taskRouter: TaskRouter

    @Before
    fun setUp() {
        taskRouter = TaskRouter()
    }

    @Test
    fun testFastLocalRouting() {
        val r1 = taskRouter.routeTask("включи фонарик")
        assertEquals(TaskType.FAST_LOCAL, r1.taskType)
        assertEquals(ModelTier.TIER_0_LOCAL, r1.tier)

        val r2 = taskRouter.routeTask("сколько батареи")
        assertEquals(TaskType.FAST_LOCAL, r2.taskType)
    }

    @Test
    fun testToolExecutionRouting() {
        val r1 = taskRouter.routeTask("позвони маме")
        assertEquals(TaskType.TOOL_EXECUTION, r1.taskType)

        val r2 = taskRouter.routeTask("открой телеграм")
        assertEquals(TaskType.TOOL_EXECUTION, r2.taskType)
    }

    @Test
    fun testSearchRouting() {
        val r1 = taskRouter.routeTask("найди в интернете последние новости")
        assertEquals(ModelTier.TIER_3_SEARCH_OSINT, r1.tier)
        assertTrue(r1.requiresWebSearch)

        val r2 = taskRouter.routeTask("кто такой Илон Маск")
        assertEquals(ModelTier.TIER_3_SEARCH_OSINT, r2.tier)
        assertTrue(r2.requiresWebSearch)
    }

    @Test
    fun testComplexPlanRouting() {
        val r1 = taskRouter.routeTask("я ухожу из дома")
        assertEquals(TaskType.COMPLEX_PLAN, r1.taskType)
        assertEquals(ModelTier.TIER_2_REASONING, r1.tier)

        val r2 = taskRouter.routeTask("составь подробный бизнес-план для стартапа")
        assertEquals(TaskType.COMPLEX_PLAN, r2.taskType)
    }

    @Test
    fun testAiConversationRouting() {
        val r1 = taskRouter.routeTask("придумай интересную тему для обсуждения")
        assertEquals(TaskType.AI_CONVERSATION, r1.taskType)
        assertEquals(ModelTier.TIER_1_FAST, r1.tier)
    }
}
