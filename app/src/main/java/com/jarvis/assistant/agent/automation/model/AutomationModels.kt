package com.jarvis.assistant.agent.automation.model

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
