package com.jarvis.assistant.agent.tools.productivity

import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class CreateAutomationTool @Inject constructor(
    private val automationEngineProvider: Provider<PersonalAutomationEngine>
) : JarvisTool {

    override val toolId: String = "productivity.create_automation"
    override val description: String = "Создаёт правило автоматизации при системных событиях (подключение наушников, Wi-Fi, низкий заряд батареи)"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Название автоматизации (например: Режим наушников)")
            }
            putJsonObject("trigger_type") {
                put("type", "string")
                put("description", "Тип триггера: HEADPHONES_CONNECTED, HEADPHONES_DISCONNECTED, BATTERY_LOW, WIFI_CONNECTED, TIME_SCHEDULE")
            }
            putJsonObject("trigger_param") {
                put("type", "string")
                put("description", "Параметр триггера: для TIME_SCHEDULE — время '07:00'")
            }
            putJsonObject("tool_action") {
                put("type", "string")
                put("description", "Идентификатор инструмента (например: media.control, device.flashlight, device.volume, device.open_app)")
            }
            putJsonObject("action_params") {
                put("type", "object")
                put("description", "Параметры для вызываемого инструмента")
            }
            putJsonObject("voice_announcement") {
                put("type", "string")
                put("description", "Голосовое подтверждение при срабатывании")
            }
        }
        put("required", buildJsonArray {
            add("trigger_type")
            add("tool_action")
        })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val rawTrigger = arguments["trigger_type"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "HEADPHONES_CONNECTED"
        val triggerType = when {
            rawTrigger.contains("TIME") || rawTrigger.contains("ВРЕМ") || rawTrigger.contains("РАСПИСАН") -> AutomationTriggerType.TIME_SCHEDULE
            rawTrigger.contains("HEADPHONE") || rawTrigger.contains("НАУШНИК") -> AutomationTriggerType.HEADPHONES_CONNECTED
            rawTrigger.contains("DISCONNECT") || rawTrigger.contains("ОТКЛЮЧ") -> AutomationTriggerType.HEADPHONES_DISCONNECTED
            rawTrigger.contains("BATTERY") || rawTrigger.contains("БАТАРЕ") -> AutomationTriggerType.BATTERY_LOW
            rawTrigger.contains("WIFI") || rawTrigger.contains("ВАЙФАЙ") -> AutomationTriggerType.WIFI_CONNECTED
            else -> AutomationTriggerType.HEADPHONES_CONNECTED
        }

        val triggerParam = arguments["trigger_param"]?.jsonPrimitive?.contentOrNull ?: ""

        val toolAction = arguments["tool_action"]?.jsonPrimitive?.contentOrNull ?: "media.control"
        val actionParams = arguments["action_params"]?.jsonObject ?: buildJsonObject { put("action", "next") }
        val name = arguments["name"]?.jsonPrimitive?.contentOrNull ?: "Автоматизация для ${triggerType.name}"
        val announcement = arguments["voice_announcement"]?.jsonPrimitive?.contentOrNull ?: "Автоматизация '$name' выполнена, сэр."

        return try {
            val call = ToolCall(toolId = toolAction, arguments = actionParams)
            val engine = automationEngineProvider.get()
            engine.createAutomationRule(
                name = name,
                triggerType = triggerType,
                triggerParam = triggerParam,
                actions = listOf(call),
                voiceAnnouncement = announcement
            )

            ToolExecutionResult.success(
                summary = "Автоматизация '$name' успешно создана",
                data = buildJsonObject {
                    put("name", name)
                    put("trigger", triggerType.name)
                    put("action", toolAction)
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось создать автоматизацию: ${e.localizedMessage}", "AUTOMATION_ERROR")
        }
    }
}
