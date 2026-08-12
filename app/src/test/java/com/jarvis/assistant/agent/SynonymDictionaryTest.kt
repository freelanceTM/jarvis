package com.jarvis.assistant.agent

import com.jarvis.assistant.agent.discovery.SynonymDictionary
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SynonymDictionary
 */
class SynonymDictionaryTest {
    
    @Test
    fun `flashlight synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("фонарик")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("свет") || it.contains("flash") })
    }
    
    @Test
    fun `volume synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("громкость")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("звук") || it.contains("volume") })
    }
    
    @Test
    fun `enable synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("включи")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("вруб") || it.contains("enable") })
    }
    
    @Test
    fun `disable synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("выключи")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("выруб") || it.contains("disable") })
    }
    
    @Test
    fun `areSynonyms returns true for related words`() {
        assertTrue(SynonymDictionary.areSynonyms("фонарик", "свет"))
        assertTrue(SynonymDictionary.areSynonyms("громкость", "звук"))
        assertTrue(SynonymDictionary.areSynonyms("включи", "активируй"))
    }
    
    @Test
    fun `areSynonyms returns false for unrelated words`() {
        assertFalse(SynonymDictionary.areSynonyms("фонарик", "батарея"))
        assertFalse(SynonymDictionary.areSynonyms("громкость", "навигация"))
    }
    
    @Test
    fun `short words return empty synonyms`() {
        val synonyms = SynonymDictionary.getSynonyms("ок")
        assertTrue(synonyms.isEmpty())
    }
    
    @Test
    fun `english words are supported`() {
        val synonyms = SynonymDictionary.getSynonyms("flashlight")
        assertTrue(synonyms.isNotEmpty())
    }
    
    @Test
    fun `call synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("позвони")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("звон") || it.contains("call") })
    }
    
    @Test
    fun `music synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("музыка")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("трек") || it.contains("music") })
    }
    
    @Test
    fun `memory synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("запомни")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("помн") || it.contains("remember") })
    }
    
    @Test
    fun `forget synonyms are found`() {
        val synonyms = SynonymDictionary.getSynonyms("забудь")
        assertTrue(synonyms.isNotEmpty())
        assertTrue(synonyms.any { it.contains("удал") || it.contains("forget") })
    }
}
