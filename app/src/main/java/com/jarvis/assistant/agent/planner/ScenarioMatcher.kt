package com.jarvis.assistant.agent.planner

/**
 * Сценарии планировщика. Приоритет = порядок проверки в исходном planForGoal
 * (меньший номер проверялся раньше и выигрывает при множественном совпадении).
 */
enum class ScenarioId(val priority: Int) {
    LEAVING_HOME(1),
    COMING_HOME(2),
    SLEEP(3),
    MORNING(4),
    MEETING(5),
    DRIVING(6),
    POWER_SAVING(7),
    DIAGNOSTICS(8),
    PRESENTATION(9),
    RESET(10)
}

/**
 * Матчер сценариев на основе ХЕШ-ТАБЛИЦЫ ключевых слов (пункт аудита #6 — HIGH).
 *
 * Заменяет последовательную цепочку из 15+ `q.contains(...)` в planForGoal:
 *  - все ключевые слова собраны в [keywordIndex]: Map<подстрока, ScenarioId>;
 *  - за ОДИН проход по словарю находятся все совпавшие сценарии;
 *  - побеждает сценарий с наименьшим приоритетом (сохраняет порядок исходных if);
 *  - комбинированные условия (оба слова обязательны) — в [compositeConditions].
 *
 * Для специфичных корней используется contains(); неоднозначные одиночные слова
 * («отчёт», «состояние», «статус») заменены явными фразами, чтобы аналитические
 * запросы не запускали диагностику телефона.
 */
object ScenarioMatcher {

    /** Хеш-таблица: ключевое слово (подстрока) → сценарий. */
    private val keywordIndex: Map<String, ScenarioId> = buildMap {
        // 1. «Я ухожу» / «Выхожу из дома» / «На выход»
        put("я ухожу", ScenarioId.LEAVING_HOME)
        put("выхожу", ScenarioId.LEAVING_HOME)
        put("вышел из дома", ScenarioId.LEAVING_HOME)
        put("на выход", ScenarioId.LEAVING_HOME)
        put("ухожу на работу", ScenarioId.LEAVING_HOME)
        put("выхожу из офиса", ScenarioId.LEAVING_HOME)

        // 2. «Я пришёл домой» / «Я дома» / «Вернулся»
        put("я пришел", ScenarioId.COMING_HOME)
        put("я дома", ScenarioId.COMING_HOME)
        put("вернулся домой", ScenarioId.COMING_HOME)
        put("пришёл домой", ScenarioId.COMING_HOME)
        put("дома уже", ScenarioId.COMING_HOME)

        // 3. «Подготовь ко сну» / «Режим сна» / «Спокойной ночи»
        put("сон", ScenarioId.SLEEP)
        put("ко сну", ScenarioId.SLEEP)
        put("режим сна", ScenarioId.SLEEP)
        put("спать", ScenarioId.SLEEP)
        put("спокойной ночи", ScenarioId.SLEEP)
        put("ночной режим", ScenarioId.SLEEP)
        put("ложусь", ScenarioId.SLEEP)
        put("засыпа", ScenarioId.SLEEP)

        // 4. «Доброе утро» / «Просыпаюсь» / «Утренний режим»
        put("доброе утро", ScenarioId.MORNING)
        put("просыпа", ScenarioId.MORNING)
        put("утренний режим", ScenarioId.MORNING)
        put("проснулся", ScenarioId.MORNING)

        // 5. «Режим совещания» / «Я на встрече» / «Митинг»
        put("совещани", ScenarioId.MEETING)
        put("встреч", ScenarioId.MEETING)
        put("митинг", ScenarioId.MEETING)
        put("на собрании", ScenarioId.MEETING)
        put("переговор", ScenarioId.MEETING)

        // 6. «Подготовь к поездке» / «Еду на машине» / «Навигация»
        put("поездк", ScenarioId.DRIVING)
        put("еду ", ScenarioId.DRIVING)
        put("за рулём", ScenarioId.DRIVING)
        put("за рулем", ScenarioId.DRIVING)
        put("в машин", ScenarioId.DRIVING)
        put("автомобил", ScenarioId.DRIVING)

        // 7. «Режим экономии» / «Батарея садится» / «Экономь заряд»
        put("эконом", ScenarioId.POWER_SAVING)
        put("батарея садится", ScenarioId.POWER_SAVING)
        put("мало заряда", ScenarioId.POWER_SAVING)
        put("экономь", ScenarioId.POWER_SAVING)
        put("сохрани заряд", ScenarioId.POWER_SAVING)

        // 8. «Статус системы» / «Что с телефоном» / «Диагностика».
        // Общие слова «отчёт», «состояние», «статус» намеренно не используем:
        // они давали ложный сценарий устройства для финансовых/технических текстов.
        put("статус системы", ScenarioId.DIAGNOSTICS)
        put("статус телефона", ScenarioId.DIAGNOSTICS)
        put("состояние системы", ScenarioId.DIAGNOSTICS)
        put("состояние телефона", ScenarioId.DIAGNOSTICS)
        put("системный отчёт", ScenarioId.DIAGNOSTICS)
        put("диагностик", ScenarioId.DIAGNOSTICS)
        put("что с телефоном", ScenarioId.DIAGNOSTICS)
        put("проверь всё", ScenarioId.DIAGNOSTICS)

        // 9. «Подготовь презентацию» / «Демо режим»
        put("презентац", ScenarioId.PRESENTATION)
        put("демо", ScenarioId.PRESENTATION)

        // 10. «Отмени всё» / «Верни как было» / «Обычный режим»
        put("отмени", ScenarioId.RESET)
        put("верни как было", ScenarioId.RESET)
        put("обычный режим", ScenarioId.RESET)
        put("стандартный режим", ScenarioId.RESET)
        put("сброс", ScenarioId.RESET)
    }

    /** Комбинированные условия: оба слова обязаны присутствовать. */
    private val compositeConditions: List<Pair<ScenarioId, Pair<String, String>>> = listOf(
        ScenarioId.MORNING to ("утро" to "режим"),
        ScenarioId.PRESENTATION to ("показ" to "режим")
    )

    /**
     * Находит сценарий по запросу (О(1) словарь + проход по ключам).
     *
     * @return сценарий с наименьшим приоритетом (порядок исходных if) или null.
     */
    fun match(query: String): ScenarioId? {
        var best: ScenarioId? = null

        // 1. Одиночные ключи — один проход по словарю.
        for ((keyword, scenario) in keywordIndex) {
            if (query.contains(keyword) && (best == null || scenario.priority < best.priority)) {
                best = scenario
            }
        }

        // 2. Комбинированные условия.
        for ((scenario, pair) in compositeConditions) {
            if (query.contains(pair.first) && query.contains(pair.second) &&
                (best == null || scenario.priority < best.priority)
            ) {
                best = scenario
            }
        }

        return best
    }

    /** Количество ключевых слов (для диагностики/тестов). */
    fun keywordCount(): Int = keywordIndex.size
}
