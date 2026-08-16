package com.jarvis.assistant.agent

import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.fast.FastRouteResult
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
    // Brightness (absolute percent / relative delta / read)
    // ===========================================

    @Test
    fun `brightness to percent routes absolute value`() {
        val result = router.route("увеличь яркость до 80%")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(80, handled.toolCall?.arguments?.get("percent")?.jsonPrimitive?.int)
    }

    @Test
    fun `brightness number without preposition routes absolute value`() {
        val result = router.route("поставь яркость 50 процентов")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(50, handled.toolCall?.arguments?.get("percent")?.jsonPrimitive?.int)
    }

    @Test
    fun `brightness increase by delta routes relative value`() {
        val result = router.route("увеличь яркость на 20")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(20, handled.toolCall?.arguments?.get("delta")?.jsonPrimitive?.int)
    }

    @Test
    fun `brightness decrease by delta routes negative relative value`() {
        val result = router.route("уменьши яркость на 20 процентов")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(-20, handled.toolCall?.arguments?.get("delta")?.jsonPrimitive?.int)
    }

    @Test
    fun `brighter without number routes +10 delta`() {
        val result = router.route("сделай ярче")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(10, handled.toolCall?.arguments?.get("delta")?.jsonPrimitive?.int)
    }

    @Test
    fun `darker without number routes -10 delta`() {
        val result = router.route("сделай темнее")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(-10, handled.toolCall?.arguments?.get("delta")?.jsonPrimitive?.int)
    }

    @Test
    fun `brightness max routes 100 percent`() {
        val result = router.route("яркость на максимум")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertEquals(100, handled.toolCall?.arguments?.get("percent")?.jsonPrimitive?.int)
    }

    @Test
    fun `brightness query without value routes read (empty arguments)`() {
        val result = router.route("какая сейчас яркость")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.brightness", handled.toolCall?.toolId)
        assertTrue(handled.toolCall?.arguments?.isEmpty() == true)
    }

    // ===========================================
    // Bluetooth & Wi-Fi (honest Android behavior)
    // ===========================================

    @Test
    fun `bluetooth enable command routes to bluetooth tool with enable action`() {
        val result = router.route("Джарвис, включи блютуз")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.bluetooth", handled.toolCall?.toolId)
        assertEquals("enable", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `bluetooth disable command routes with disable action`() {
        val result = router.route("выключи bluetooth")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.bluetooth", handled.toolCall?.toolId)
        assertEquals("disable", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `bluetooth toggle command routes with toggle action`() {
        val result = router.route("переключи блютуз")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.bluetooth", handled.toolCall?.toolId)
        assertEquals("toggle", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `bluetooth without verb routes to status`() {
        val result = router.route("как там блютуз")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.bluetooth", handled.toolCall?.toolId)
        assertEquals("status", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `wifi enable command routes to wifi tool with enable action`() {
        val result = router.route("включи вайфай")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.wifi", handled.toolCall?.toolId)
        assertEquals("enable", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `wifi disable command routes with disable action`() {
        val result = router.route("выключи wi-fi")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.wifi", handled.toolCall?.toolId)
        assertEquals("disable", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `wifi without verb routes to status`() {
        val result = router.route("есть ли интернет")
        assertTrue(result is FastRouteResult.HandledLocally)

        val handled = result as FastRouteResult.HandledLocally
        assertEquals("device.wifi", handled.toolCall?.toolId)
        assertEquals("status", handled.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
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
