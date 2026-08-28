package com.jarvis.assistant.agent.memory.extractor

import com.jarvis.assistant.agent.memory.model.ExtractedMemory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory Governance Extractor (Memory 2.0)
 * Анализирует каждую реплику: отсекает мусор, классифицирует по типам (Fact, Preference, Episodic, Procedural),
 * вычисляет важность (Importance) и распознает команды на забывание ("Забудь, что...").
 */
@Singleton
class AutonomousMemoryExtractor @Inject constructor() {

    fun extractFromTurn(userMessage: String): ExtractedMemory? {
        val q = userMessage.trim()
        val lower = q.lowercase()

        // =========================================================================
        // 🗑️ 1. Распознавание команд на забывание ("Забудь, что я...", "Удали из памяти")
        // =========================================================================
        if (lower.startsWith("забудь") || lower.startsWith("удали из памяти") || lower.startsWith("сотри из памяти") || lower.startsWith("не помни")) {
            val target = q.replace(Regex("(?i)^(забудь,|забудь|удали из памяти|сотри из памяти|не помни)\\s*"), "")
                .replace(Regex("(?i)^(что я|что у меня|обо мне|про)\\s*"), "").trim()
                .replace(Regex("[.!?]"), "")

            return ExtractedMemory(
                shouldRemember = false,
                type = "FACT",
                key = target,
                value = target,
                content = target,
                governanceAction = "DELETE_FORGET"
            )
        }

        // =========================================================================
        // 🚫 2. Фильтр мусора (0% засорения базы данных)
        // =========================================================================
        if (isDiscardableNoise(lower)) {
            return null
        }

        // =========================================================================
        // 👨‍👩‍👧 3. Семья и близкие (Мама, папа, жена, муж, дети, друзья)
        // =========================================================================
        if (lower.contains("маму зовут") || lower.contains("мою маму зовут") || lower.contains("имя мамы") || lower.contains("мама —")) {
            val name = q.replace(Regex("(?i)^(мою маму зовут|маму зовут|имя мамы|мама —|мама:)\\s*"), "").trim()
                .replace(Regex("[.!?,]"), "").capitalizeFirst()
            if (name.isNotEmpty() && name.length in 2..30) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = "family.mother",
                    value = name,
                    content = "Маму пользователя зовут $name",
                    importance = 0.95f,
                    confidence = 1.0f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        if (lower.contains("папу зовут") || lower.contains("моего папу зовут") || lower.contains("имя папы") || lower.contains("отец —")) {
            val name = q.replace(Regex("(?i)^(моего папу зовут|папу зовут|имя папы|отец —|папа:)\\s*"), "").trim()
                .replace(Regex("[.!?,]"), "").capitalizeFirst()
            if (name.isNotEmpty() && name.length in 2..30) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = "family.father",
                    value = name,
                    content = "Папу пользователя зовут $name",
                    importance = 0.95f,
                    confidence = 1.0f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        if (lower.contains("жену зовут") || lower.contains("мою жену зовут") || lower.contains("мужа зовут") || lower.contains("моего мужа зовут")) {
            val isWife = lower.contains("жен")
            val relation = if (isWife) "Жену" else "Мужа"
            val key = if (isWife) "family.wife" else "family.husband"
            val name = q.replace(Regex("(?i)^(мою жену зовут|жену зовут|моего мужа зовут|мужа зовут)\\s*"), "").trim()
                .replace(Regex("[.!?,]"), "").capitalizeFirst()
            if (name.isNotEmpty() && name.length in 2..30) {
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = key,
                    value = name,
                    content = "$relation пользователя зовут $name",
                    importance = 0.95f,
                    confidence = 1.0f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        // =========================================================================
        // 👤 4. Классификация: FACT (Имя, машина, город, работа, контакты)
        // =========================================================================
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
                    confidence = 1.0f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        if (lower.contains("моя машина") || lower.contains("у меня машина") || lower.contains("я вожу") || lower.contains("хочу купить") || lower.contains("купил")) {
            val car = q.replace(Regex("(?i)^(моя машина|у меня машина|я вожу|хочу купить|купил)\\s*"), "").trim()
                .replace(Regex("[.!?]"), "")
            if (car.isNotEmpty()) {
                val key = if (lower.contains("машин") || lower.contains("bmw") || lower.contains("авто")) "user.car" else "user.wishlist"
                return ExtractedMemory(
                    shouldRemember = true,
                    type = "FACT",
                    key = key,
                    value = car,
                    content = "Пользователь владеет/хочет: $car",
                    importance = 0.85f,
                    confidence = 0.95f
                )
            }
        }

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
                    importance = 0.90f,
                    confidence = 0.95f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        if (lower.contains("я работаю") || lower.contains("моя профессия") || lower.contains("я разработчик") || lower.contains("моя компания")) {
            return ExtractedMemory(
                shouldRemember = true,
                type = "FACT",
                key = "user.occupation",
                value = q,
                content = "Профессия/компания пользователя: $q",
                importance = 0.85f,
                confidence = 0.90f
            )
        }

        // =========================================================================
        // ☕ 5. Классификация: PREFERENCE (Привычки, время сна, предпочтения)
        // =========================================================================
        if (lower.contains("ложусь спать в") || lower.contains("просыпаюсь в") || lower.contains("мой режим")) {
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
                    importance = 0.80f,
                    confidence = 0.95f,
                    governanceAction = "UPDATE_EXISTING"
                )
            }
        }

        if (lower.contains("мне нравится") || lower.contains("я предпочитаю") || lower.contains("мой любимый") || lower.contains("моя любимая")) {
            return ExtractedMemory(
                shouldRemember = true,
                type = "PREFERENCE",
                key = "user.preference",
                value = q,
                content = "Предпочтение пользователя: $q",
                importance = 0.70f,
                confidence = 0.85f
            )
        }

        // =========================================================================
        // ⚙️ 6. Классификация: PROCEDURAL (Сценарии "когда я говорю... делай...")
        // =========================================================================
        if (lower.contains("когда я говорю") || lower.contains("по команде") || lower.contains("запомни сценарий")) {
            return ExtractedMemory(
                shouldRemember = true,
                type = "PROCEDURAL",
                key = "macro.custom",
                value = q,
                content = "Пользовательский сценарий: $q",
                importance = 0.95f,
                confidence = 0.90f
            )
        }

        return null
    }

    private fun isDiscardableNoise(text: String): Boolean {
        // Команды устройства, приветствия, запросы времени, погоды, общие вопросы
        return text.startsWith("включи") ||
                text.startsWith("выключи") ||
                text.startsWith("открой") ||
                text.startsWith("сделай громче") ||
                text.startsWith("тише") ||
                text.startsWith("сколько времени") ||
                text.startsWith("который час") ||
                text.startsWith("погода") ||
                text.startsWith("что такое") ||
                text.startsWith("почему") ||
                text.startsWith("привет") ||
                text.startsWith("ты тут") ||
                text.length < 5
    }

    private fun String.capitalizeFirst(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
