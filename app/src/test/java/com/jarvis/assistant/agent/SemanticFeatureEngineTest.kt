package com.jarvis.assistant.agent

import com.jarvis.assistant.agent.memory.semantic.SemanticFeatureEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SemanticFeatureEngine
 */
class SemanticFeatureEngineTest {
    
    private lateinit var engine: SemanticFeatureEngine
    
    @Before
    fun setup() {
        engine = SemanticFeatureEngine()
    }
    
    @Test
    fun `empty text returns zero vector`() {
        val vector = engine.featurize("")
        assertTrue(vector.all { it == 0f })
    }
    
    @Test
    fun `vector is normalized to unit length`() {
        val vector = engine.featurize("включи фонарик")
        val magnitude = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
        assertEquals(1.0, magnitude, 0.01)
    }
    
    @Test
    fun `similar phrases have high cosine similarity`() {
        val vec1 = engine.featurize("включи фонарик")
        val vec2 = engine.featurize("зажги свет")
        val similarity = engine.computeCosineSimilarity(vec1, vec2)
        
        // Должны быть похожи (оба про свет/фонарик)
        assertTrue("Similarity should be > 0.3, was $similarity", similarity > 0.3f)
    }
    
    @Test
    fun `different concepts have low cosine similarity`() {
        val vec1 = engine.featurize("включи фонарик")
        val vec2 = engine.featurize("позвони маме")
        val similarity = engine.computeCosineSimilarity(vec1, vec2)
        
        // Должны быть разными
        assertTrue("Similarity should be < 0.5, was $similarity", similarity < 0.5f)
    }
    
    @Test
    fun `synonyms map to same semantic cluster`() {
        val vec1 = engine.featurize("громкость")
        val vec2 = engine.featurize("звук")
        val similarity = engine.computeCosineSimilarity(vec1, vec2)
        
        assertTrue("Synonyms should have high similarity, was $similarity", similarity > 0.5f)
    }
    
    @Test
    fun `serialization and deserialization works`() {
        val original = engine.featurize("тестовый текст")
        val serialized = engine.serializeVector(original)
        val deserialized = engine.deserializeVector(serialized)
        
        assertEquals(original.size, deserialized.size)
        for (i in original.indices) {
            assertEquals(original[i], deserialized[i], 0.0001f)
        }
    }
    
    @Test
    fun `cosine similarity is symmetric`() {
        val vec1 = engine.featurize("первый текст")
        val vec2 = engine.featurize("второй текст")
        
        val sim1 = engine.computeCosineSimilarity(vec1, vec2)
        val sim2 = engine.computeCosineSimilarity(vec2, vec1)
        
        assertEquals(sim1, sim2, 0.0001f)
    }
    
    @Test
    fun `identical texts have similarity 1`() {
        val text = "одинаковый текст"
        val vec1 = engine.featurize(text)
        val vec2 = engine.featurize(text)
        
        val similarity = engine.computeCosineSimilarity(vec1, vec2)
        assertEquals(1.0f, similarity, 0.001f)
    }
}
