package com.jarvis.assistant.agent

import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.fast.FastRouteResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FastCommandRouter
 */
class FastCommandRouterTest {
    
    private lateinit var router: FastCommandRouter
    
    @Before
    fun setup() {
        router = FastCommandRouter()
    }
    
    // ===========================================
    // Flashlight Commands
    // ===========================================
    
    @Test
    fun `flashlight on command is handled locally`() {
        val result = router.route("включи фонарик")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.flashlight", handled.toolCall?.toolId)
    }
    
    @Test
    fun `flashlight off command is handled locally`() {
        val result = router.route("выключи фонарик")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.flashlight", handled.toolCall?.toolId)
    }
    
    @Test
    fun `flashlight with wake word is handled`() {
        val result = router.route("Джарвис, включи фонарик")
        assertTrue(result is FastRouteResult.HandledLocally)
    }
    
    // ===========================================
    // Volume Commands
    // ===========================================
    
    @Test
    fun `volume up command is handled locally`() {
        val result = router.route("сделай громче")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.volume", handled.toolCall?.toolId)
    }
    
    @Test
    fun `volume down command is handled locally`() {
        val result = router.route("сделай тише")
        assertTrue(result is FastRouteResult.HandledLocally)
    }
    
    @Test
    fun `volume percent command is handled locally`() {
        val result = router.route("установи громкость на 50 процентов")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.volume", handled.toolCall?.toolId)
    }
    
    @Test
    fun `mute command is handled locally`() {
        val result = router.route("выключи звук")
        assertTrue(result is FastRouteResult.HandledLocally)
    }
    
    // ===========================================
    // Battery Commands
    // ===========================================
    
    @Test
    fun `battery status command is handled locally`() {
        val result = router.route("сколько заряда батареи")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("system.battery", handled.toolCall?.toolId)
    }
    
    // ===========================================
    // Time Commands
    // ===========================================
    
    @Test
    fun `time command is handled locally`() {
        val result = router.route("который час")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("system.time", handled.toolCall?.toolId)
    }
    
    // ===========================================
    // Media Commands
    // ===========================================
    
    @Test
    fun `pause music command is handled locally`() {
        val result = router.route("поставь на паузу")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("media.control", handled.toolCall?.toolId)
    }
    
    @Test
    fun `next track command is handled locally`() {
        val result = router.route("следующий трек")
        assertTrue(result is FastRouteResult.HandledLocally)
    }
    
    // ===========================================
    // Greetings
    // ===========================================
    
    @Test
    fun `greeting is handled locally without tool call`() {
        val result = router.route("привет")
        assertTrue(result is FastRouteResult.HandledLocally)
        
        val handled = result as FastRouteResult.HandledLocally
        assertNull(handled.toolCall)
        assertTrue(handled.immediateVoiceResponse.isNotBlank())
    }
    
    // ===========================================
    // Forward to LLM
    // ===========================================
    
    @Test
    fun `complex question is forwarded to LLM`() {
        val result = router.route("объясни квантовую физику")
        assertTrue(result is FastRouteResult.ForwardToLlm)
    }
    
    @Test
    fun `unknown command is forwarded to LLM`() {
        val result = router.route("сделай что-нибудь необычное")
        assertTrue(result is FastRouteResult.ForwardToLlm)
    }
    
    @Test
    fun `empty query is forwarded to LLM`() {
        val result = router.route("")
        assertTrue(result is FastRouteResult.ForwardToLlm)
    }
}
