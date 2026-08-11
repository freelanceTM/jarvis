package com.jarvis.assistant.agent.automation.model

import com.jarvis.assistant.agent.model.ToolCall
import kotlinx.serialization.Serializable

enum class AutomationTriggerType {
    HEADPHONES_CONNECTED,   // Подключение Bluetooth/проводных наушников
    HEADPHONES_DISCONNECTED,// Отключение наушников
    BATTERY_LOW,            // Заряд опустился ниже порога
    WIFI_CONNECTED,         // Подключение к сети Wi-Fi
    TIME_SCHEDULE,          // Точное время
    VOICE_MACRO             // Голосовой триггер ("сон", "работа", "утро")
}

@Serializable
data class TimeRangeCondition(
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 59
)

@Serializable
data class AutomationRuleDto(
    val ruleId: String,
    val name: String,
    val triggerType: String,
    val triggerParam: String = "",
    val timeCondition: TimeRangeCondition? = null,
    val actions: List<AutomationActionDto> = emptyList(),
    val voiceAnnouncement: String = "",
    val isEnabled: Boolean = true
)

@Serializable
data class AutomationActionDto(
    val tool: String,
    val arguments: Map<String, String> = emptyMap()
)
