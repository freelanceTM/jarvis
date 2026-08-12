package com.jarvis.assistant.agent.fast

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FastCommandRouterTest {

    private lateinit var router: FastCommandRouter

    @Before
    fun setUp() {
        router = FastCommandRouter()
    }

    // 1. Память
    @Test
    fun testMemoryForgetCommand() {
        val result = router.route("Джарвис, забудь что я люблю BMW")
        assertTrue(result is FastRouteResult.HandledLocally)
        val handled = result as FastRouteResult.HandledLocally
        assertEquals("memory.forget", handled.toolCall?.toolId)
        assertTrue(handled.toolCall?.arguments?.get("target")?.jsonPrimitive?.content?.contains("BMW") == true)
    }

    // 2. Приветствия
    @Test
    fun testGreetings() {
        val r1 = router.route("Привет, Джарвис")
        assertTrue(r1 is FastRouteResult.HandledLocally)

        val r2 = router.route("Джарвис, ты тут?")
        assertTrue(r2 is FastRouteResult.HandledLocally)
    }

    // 3. Медиа
    @Test
    fun testMediaControl() {
        val pause = router.route("пауза") as FastRouteResult.HandledLocally
        assertEquals("media.control", pause.toolCall?.toolId)
        assertEquals("pause", pause.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)

        val next = router.route("следующий трек") as FastRouteResult.HandledLocally
        assertEquals("next", next.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)

        val prev = router.route("предыдущий трек") as FastRouteResult.HandledLocally
        assertEquals("previous", prev.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    // 4. Фонарик
    @Test
    fun testFlashlight() {
        val on = router.route("Джарвис, включи фонарик") as FastRouteResult.HandledLocally
        assertEquals("device.flashlight", on.toolCall?.toolId)
        assertEquals(true, on.toolCall?.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull)

        val off = router.route("выключи фонарик") as FastRouteResult.HandledLocally
        assertEquals(false, off.toolCall?.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull)
    }

    // 5. Громкость
    @Test
    fun testVolume() {
        val percent = router.route("громкость 70%") as FastRouteResult.HandledLocally
        assertEquals("device.volume", percent.toolCall?.toolId)
        assertEquals("set", percent.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
        assertEquals(70, percent.toolCall?.arguments?.get("percent")?.jsonPrimitive?.intOrNull)

        val max = router.route("громкость на максимум") as FastRouteResult.HandledLocally
        assertEquals("max", max.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)

        val mute = router.route("выключи звук") as FastRouteResult.HandledLocally
        assertEquals("mute", mute.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)

        val louder = router.route("сделай громче") as FastRouteResult.HandledLocally
        assertEquals("up", louder.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)

        val quieter = router.route("сделай тише") as FastRouteResult.HandledLocally
        assertEquals("down", quieter.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    // 6. Батарея
    @Test
    fun testBattery() {
        val result = router.route("сколько процентов батарея") as FastRouteResult.HandledLocally
        assertEquals("system.battery", result.toolCall?.toolId)
    }

    // 7. Время
    @Test
    fun testTime() {
        val result = router.route("который час") as FastRouteResult.HandledLocally
        assertEquals("system.time", result.toolCall?.toolId)
    }

    // 8. Скриншот
    @Test
    fun testScreenshot() {
        val result = router.route("сделай скриншот") as FastRouteResult.HandledLocally
        assertEquals("device.screenshot", result.toolCall?.toolId)
    }

    // 9. Accessibility: Screen Reader & Click
    @Test
    fun testScreenReaderAndUiClick() {
        val read = router.route("Джарвис, что на экране?") as FastRouteResult.HandledLocally
        assertEquals("accessibility.screen_reader", read.toolCall?.toolId)

        val click = router.route("нажми на Войти") as FastRouteResult.HandledLocally
        assertEquals("accessibility.ui_click", click.toolCall?.toolId)
        assertEquals("click", click.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
        assertEquals("Войти", click.toolCall?.arguments?.get("target")?.jsonPrimitive?.content)

        val scroll = router.route("прокрути вниз") as FastRouteResult.HandledLocally
        assertEquals("accessibility.ui_click", scroll.toolCall?.toolId)
        assertEquals("scroll_down", scroll.toolCall?.arguments?.get("action")?.jsonPrimitive?.content)
    }

    // 10. DND
    @Test
    fun testDnd() {
        val on = router.route("включи режим не беспокоить") as FastRouteResult.HandledLocally
        assertEquals("device.dnd", on.toolCall?.toolId)
        assertEquals(true, on.toolCall?.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull)
    }

    // 11. Навигация
    @Test
    fun testNavigation() {
        val result = router.route("навигатор в аэропорт") as FastRouteResult.HandledLocally
        assertEquals("location.navigation", result.toolCall?.toolId)
        assertEquals("аэропорт", result.toolCall?.arguments?.get("destination")?.jsonPrimitive?.content)
    }

    // 12. Звонки
    @Test
    fun testCall() {
        val result = router.route("позвони маме") as FastRouteResult.HandledLocally
        assertEquals("communication.call", result.toolCall?.toolId)
        assertEquals("маме", result.toolCall?.arguments?.get("recipient")?.jsonPrimitive?.content)
    }

    // 13. Приложения
    @Test
    fun testOpenApps() {
        val tg = router.route("открой тг") as FastRouteResult.HandledLocally
        assertEquals("device.open_app", tg.toolCall?.toolId)
        assertEquals("telegram", tg.toolCall?.arguments?.get("app_name")?.jsonPrimitive?.content)

        val yt = router.route("запусти ютуб") as FastRouteResult.HandledLocally
        assertEquals("youtube", yt.toolCall?.arguments?.get("app_name")?.jsonPrimitive?.content)

        val cam = router.route("включи камеру") as FastRouteResult.HandledLocally
        assertEquals("camera", cam.toolCall?.arguments?.get("app_name")?.jsonPrimitive?.content)
    }

    // 14. Bluetooth & Wi-Fi
    @Test
    fun testBluetoothAndWifi() {
        val bt = router.route("открой блютуз") as FastRouteResult.HandledLocally
        assertEquals("device.bluetooth", bt.toolCall?.toolId)

        val wifi = router.route("настройки вайфай") as FastRouteResult.HandledLocally
        assertEquals("device.wifi", wifi.toolCall?.toolId)
    }

    // 15. Автоматизации
    @Test
    fun testAutomationCreation() {
        val result = router.route("когда подключатся наушники включи музыку") as FastRouteResult.HandledLocally
        assertEquals("productivity.create_automation", result.toolCall?.toolId)
        assertEquals("HEADPHONES_CONNECTED", result.toolCall?.arguments?.get("trigger_type")?.jsonPrimitive?.content)
        assertEquals("media.control", result.toolCall?.arguments?.get("tool_action")?.jsonPrimitive?.content)
    }

    // 16. Нераспознанный сложный запрос -> ForwardToLlm
    @Test
    fun testComplexConversationForwardsToLlm() {
        val result = router.route("почему трава зеленая а небо синее")
        assertEquals(FastRouteResult.ForwardToLlm, result)
    }
}
