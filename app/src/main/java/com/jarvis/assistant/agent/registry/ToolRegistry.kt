package com.jarvis.assistant.agent.registry

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val toolsSet: Set<@JvmSuppressWildcards JarvisTool>
) {
    private val toolsMap: Map<String, JarvisTool> = toolsSet.associateBy { it.name }

    fun getAllTools(): List<JarvisTool> = toolsMap.values.toList()

    fun getTool(name: String): JarvisTool? = toolsMap[name]

    suspend fun executeTool(toolName: String, arguments: Map<String, String>): ToolResult {
        val tool = getTool(toolName)
            ?: return ToolResult.Failure(
                summary = "Инструмент '$toolName' не найден",
                errorMessage = "ToolNotFound: $toolName"
            )

        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            ToolResult.Failure(
                summary = "Ошибка при выполнении $toolName: ${e.localizedMessage}",
                errorMessage = e.localizedMessage ?: "Unknown error"
            )
        }
    }

    /**
     * Генерирует четкий системный промпт с реальными примерами для надежного Function Calling
     */
    fun buildToolsSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты автономный агент JARVIS, управляющий телефоном Android.\n")
        sb.append("Список доступных системных инструментов:\n")
        toolsMap.values.forEach { tool ->
            sb.append("- Имя инструмента: \"${tool.name}\". Назначение: ${tool.description}.\n")
        }
        sb.append("\nПРАВИЛА ВЫЗОВА ИНСТРУМЕНТОВ (Строгий формат JSON):\n")
        sb.append("1. Запуск приложений (Telegram, YouTube, WhatsApp, Камера, Музыка, Хром):\n")
        sb.append("ACTION_CALL: {\"tool\": \"open_app\", \"params\": {\"app_name\": \"telegram\"}}\n")
        sb.append("2. Управление громкостью (громче, тише, выключить, процент):\n")
        sb.append("ACTION_CALL: {\"tool\": \"set_volume\", \"params\": {\"action\": \"up\"}}\n")
        sb.append("3. Настройки телефона (Bluetooth, Wi-Fi, Батарея, Экран):\n")
        sb.append("ACTION_CALL: {\"tool\": \"open_settings\", \"params\": {\"target\": \"bluetooth\"}}\n")
        sb.append("4. Таймеры и будильники:\n")
        sb.append("ACTION_CALL: {\"tool\": \"set_timer_alarm\", \"params\": {\"type\": \"timer\", \"value\": \"5\"}}\n")
        sb.append("5. Если команда пользователя - обычный разговор, отвечай текстом кратко в 1-2 предложения.\n")
        return sb.toString()
    }
}
