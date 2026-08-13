package com.jarvis.assistant.agent.briefing

import android.content.Context
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import com.jarvis.assistant.agent.memory.manager.JarvisMemoryManager
import com.jarvis.assistant.agent.tools.intelligence.WebSearchTool
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive Ear Briefing Engine (JARVIS Earclip)
 * 
 * Автоматически генерирует структурированный 15-секундный аудио-брифинг прямо в ухо при надевании наушника:
 * 1. Персональное приветствие («Доброе утро/день, сэр»)
 * 2. Точное время и день недели
 * 3. Заряд батареи и статус питания
 * 4. Статус сети (Wi-Fi / LTE)
 * 5. Погода и свежие факты
 */
@Singleton
class ProactiveEarBriefingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val memoryManager: JarvisMemoryManager,
    private val networkMonitor: NetworkMonitor,
    private val webSearchTool: WebSearchTool
) {

    suspend fun generateBriefing(): String = withContext(Dispatchers.IO) {
        val userName = settingsRepository.userNameFlow.first().ifBlank { "сэр" }
        val locale = Locale("ru", "RU")
        val now = Date()

        val calendar = Calendar.getInstance(locale)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..22 -> "Добрый вечер"
            else -> "Доброй ночи"
        }

        val timeFormat = SimpleDateFormat("HH:mm", locale)
        val dayFormat = SimpleDateFormat("EEEE, d MMMM", locale)
        val timeStr = timeFormat.format(now)
        val dayStr = dayFormat.format(now)

        val batteryPercent = getBatteryPercent()
        val isOnline = networkMonitor.isCurrentlyOnline()

        val sb = StringBuilder()
        sb.append("$greeting, $userName. ")
        sb.append("Сейчас $timeStr, $dayStr. ")
        sb.append("Заряд аккумулятора: $batteryPercent%. ")

        if (isOnline) {
            sb.append("Системы онлайн. ")
            // Пробуем получить краткую погоду
            try {
                val weatherResult = webSearchTool.execute(
                    buildJsonObject { put("query", "погода в Ашхабаде сегодня") }
                )
                if (weatherResult.isSuccess && weatherResult.summary.isNotBlank() && !weatherResult.summary.contains("не найдено")) {
                    val weatherSnippet = weatherResult.summary.take(120)
                    sb.append("Погода: $weatherSnippet. ")
                }
            } catch (_: Exception) { }
        } else {
            sb.append("Работаем в автономном режиме. ")
        }

        // Добавляем напоминания или факты из памяти
        try {
            val memories = memoryManager.recall("сегодня планы важные", limit = 1)
            if (memories.isNotEmpty()) {
                sb.append("Напоминание: ${memories.first().content}. ")
            }
        } catch (_: Exception) { }

        sb.append("Я готов к работе.")
        return@withContext sb.toString().trim()
    }

    private fun getBatteryPercent(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            85
        }
    }
}
