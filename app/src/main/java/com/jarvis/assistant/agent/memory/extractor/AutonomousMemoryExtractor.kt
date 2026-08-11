package com.jarvis.assistant.agent.memory.extractor

import com.jarvis.assistant.agent.memory.model.ExtractedMemory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutonomousMemoryExtractor @Inject constructor() {

    /**
     * Автономный локальный анализатор диалога (определяет, стоит ли запомнить факт)
     */
    fun extractFromTurn(userMessage: String): ExtractedMemory? {
        val q = userMessage.trim()
        val lower = q.lowercase()

        // 1. Имя пользователя
        if (lower.contains("меня зовут") || lower.contains("мое имя") || lower.contains("называй меня")) {
            val name = q.replace(Regex("(?i)^(меня зовут|мое имя|называй меня|зови меня)\\s*"), "").trim()
                .replace(Regex("[.!?,]"), "").capitalizeFirst()
            if (name.isNotEmpty() && name.length in 2..30) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = "user.name",
                    value = name,
                    content = "Имя пользователя: $name",
                    importance = 1.0f,
                    confidence = 1.0f
                )
            }
        }

        // 2. Автомобиль
        if (lower.contains("моя машина") || lower.contains("у меня машина") || lower.contains("я вожу") || lower.contains("хочу купить машину")) {
            val car = q.replace(Regex("(?i)^(моя машина|у меня машина|я вожу|хочу купить машину)\\s*"), "").trim()
                .replace(Regex("[.!?]"), "")
            if (car.isNotEmpty()) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = "user.car",
                    value = car,
                    content = "Автомобиль пользователя: $car",
                    importance = 0.85f,
                    confidence = 0.95f
                )
            }
        }

        // 3. Город / Локация
        if (lower.contains("я живу в") || lower.contains("мой город") || lower.contains("переехал в")) {
            val city = q.replace(Regex("(?i)^(я живу в|мой город|переехал в)\\s*"), "").trim()
                .replace(Regex("[.!?]"), "").capitalizeFirst()
            if (city.isNotEmpty()) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = "user.city",
                    value = city,
                    content = "Город проживания пользователя: $city",
                    importance = 0.9f,
                    confidence = 0.95f
                )
            }
        }

        // 4. Время сна / Привычки
        if (lower.contains("ложусь спать в") || lower.contains("просыпаюсь в")) {
            val timeMatch = Regex("""\d{1,2}(:\d{2})?""").find(lower)
            if (timeMatch != null) {
                val isSleep = lower.contains("спать")
                val key = if (isSleep) "sleep.time" else "wake.time"
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "PREFERENCE",
                    key = key,
                    value = timeMatch.value,
                    content = if (isSleep) "Пользователь ложится спать в ${timeMatch.value}" else "Пользователь просыпается в ${timeMatch.value}",
                    importance = 0.75f,
                    confidence = 0.9f
                )
            }
        }

        // 5. Профессия / Работа
        if (lower.contains("я работаю") || lower.contains("моя профессия") || lower.contains("я разработчик") || lower.contains("я дизайнер")) {
            return ExtractedMemory(
                shouldRemember = true,
                type = "FACT",
                key = "user.occupation",
                value = q,
                content = "Профессия/работа пользователя: $q",
                importance = 0.85f,
                confidence = 0.9f
            )
        }

        return null
    }

    private fun String.capitalizeFirst(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
