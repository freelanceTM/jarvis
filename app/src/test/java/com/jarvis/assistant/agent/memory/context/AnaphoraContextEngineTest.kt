package com.jarvis.assistant.agent.memory.context

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AnaphoraContextEngineTest {

    private lateinit var engine: AnaphoraContextEngine

    @Before
    fun setUp() {
        engine = AnaphoraContextEngine()
    }

    @Test
    fun testPronounDetection() {
        assertTrue(engine.hasContextualPronoun("Сколько ему лет?"))
        assertTrue(engine.hasContextualPronoun("Где он родился?"))
        assertTrue(engine.hasContextualPronoun("Что о нем известно?"))
        assertTrue(engine.hasContextualPronoun("Какая там погода?"))
        assertFalse(engine.hasContextualPronoun("Включи фонарик"))
    }

    @Test
    fun testQueryResolutionWithPresidentMacron() {
        val lastEntity = "Эмманюэль Макрон"
        val query = "Сколько ему лет?"
        val resolved = engine.resolveQuery(query, lastEntity)

        assertEquals("сколько лет Эмманюэль Макрон", resolved)
    }

    @Test
    fun testQueryResolutionWhereHeLives() {
        val lastEntity = "Илон Маск"
        val query = "Где он живет?"
        val resolved = engine.resolveQuery(query, lastEntity)

        assertTrue(resolved.contains("Илон Маск"))
    }

    @Test
    fun testQueryResolutionWithLocation() {
        val lastEntity = "Париж"
        val query = "Какая там погода?"
        val resolved = engine.resolveQuery(query, lastEntity)

        assertEquals("Какая в Париж погода?", resolved)
    }

    @Test
    fun testEntityExtractionFromAssistantResponse() {
        val response = "Президентом Франции является Эмманюэль Макрон."
        val extracted = engine.extractEntity(response)

        assertEquals("Эмманюэль Макрон", extracted)
    }

    @Test
    fun testEntityExtractionFromUserQuery() {
        val query = "Кто такой Илон Маск?"
        val extracted = engine.extractEntity(query)

        assertEquals("Илон Маск", extracted)
    }
}
