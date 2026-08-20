package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.benchmark.BenchmarkCategory.AGENT
import com.jarvis.assistant.benchmark.BenchmarkCategory.AMBIGUOUS
import com.jarvis.assistant.benchmark.BenchmarkCategory.CLOUD_AI
import com.jarvis.assistant.benchmark.BenchmarkCategory.CLOUD_WEB
import com.jarvis.assistant.benchmark.BenchmarkCategory.DEVICE
import com.jarvis.assistant.benchmark.BenchmarkCategory.EDGE_CASE
import com.jarvis.assistant.benchmark.BenchmarkCategory.LOCAL_AI
import com.jarvis.assistant.benchmark.BenchmarkCategory.PRIVACY
import com.jarvis.assistant.benchmark.ExpectedExecutionType as Exp

/**
 * JARVIS Benchmark Dataset v1 — 100 команд.
 *
 * ХАРАКТЕР НАБОРА: **синтетический** (п. 42 ТЗ). Команды написаны вручную по
 * реальным сценариям голосового ассистента, но НЕ собраны из логов живых
 * пользователей — таких логов у проекта нет. Это прямо отражено в отчёте.
 *
 * Принципы формирования ground truth:
 *
 * 1. Ожидание строится от РЕАЛЬНЫХ возможностей проекта, а не от желаемого.
 *    Например, у `productivity.alarm_timer` есть тул, но FastCommandRouter
 *    его не вызывает — такие случаи помечены [BenchmarkCase.knownGap]
 *    и остаются в метриках как честные находки.
 *
 * 2. Ground truth зафиксирован ДО первого запуска и не правился под факт.
 *
 * 3. Набор НЕ подогнан под высокий процент Local AI. Наоборот: 25 device,
 *    22 local, 15 cloud, 10 web, 10 agent, 6 ambiguous, 7 edge, 5 privacy —
 *    пропорция отражает типичное использование ассистента, где команды
 *    устройству преобладают.
 */
object BenchmarkDataset {

    const val VERSION = "1.0.0"

    /** Синтетический набор — источник указывается в отчёте. */
    const val NATURE = "synthetic (hand-written from realistic assistant scenarios)"

    val cases: List<BenchmarkCase> = buildList {

        // ================================================================
        // DEVICE — 25. Управление устройством через ToolExecutor/JarvisTool.
        // ================================================================
        add(BenchmarkCase("DEVICE-001", DEVICE, "Открой Telegram", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-002", DEVICE, "Открой YouTube", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-003", DEVICE, "Открой настройки", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app → settings", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-004", DEVICE, "Открой камеру", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app → camera", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-005", DEVICE, "Запусти WhatsApp", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-006", DEVICE, "Открой калькулятор", Exp.DEVICE_TOOL,
            "Роутер секция 12: device.open_app", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-007", DEVICE, "Сделай громче", Exp.DEVICE_TOOL,
            "Роутер секция 5: device.volume", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-008", DEVICE, "Убавь громкость", Exp.DEVICE_TOOL,
            "Роутер секция 5: device.volume", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-009", DEVICE, "Установи громкость 50 процентов", Exp.DEVICE_TOOL,
            "Роутер секция 5: device.volume с percent", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-010", DEVICE, "Выключи звук", Exp.DEVICE_TOOL,
            "Роутер секция 5: device.volume mute", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-011", DEVICE, "Включи фонарик", Exp.DEVICE_TOOL,
            "Роутер секция 4: device.flashlight", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-012", DEVICE, "Выключи фонарик", Exp.DEVICE_TOOL,
            "Роутер секция 4: device.flashlight", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-013", DEVICE, "Сделай экран ярче", Exp.DEVICE_TOOL,
            "Роутер секция 5.1: device.brightness", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-014", DEVICE, "Установи яркость 30 процентов", Exp.DEVICE_TOOL,
            "Роутер секция 5.1: device.brightness", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-015", DEVICE, "Включи Wi-Fi", Exp.DEVICE_TOOL,
            "Роутер секция 13: device.wifi", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-016", DEVICE, "Выключи блютуз", Exp.DEVICE_TOOL,
            "Роутер секция 13: device.bluetooth", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-017", DEVICE, "Сколько заряда батареи", Exp.DEVICE_TOOL,
            "Роутер секция 6: system.battery", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-018", DEVICE, "Который час", Exp.DEVICE_TOOL,
            "Роутер секция 7: system.time", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-019", DEVICE, "Сделай скриншот", Exp.DEVICE_TOOL,
            "Роутер секция 8: device.screenshot", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-020", DEVICE, "Включи режим не беспокоить", Exp.DEVICE_TOOL,
            "Роутер секция 9: device.dnd", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-021", DEVICE, "Включи музыку", Exp.DEVICE_TOOL,
            "Роутер секция 3: media.control PLAY", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-022", DEVICE, "Следующий трек", Exp.DEVICE_TOOL,
            "Роутер секция 3: media.control NEXT", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-023", DEVICE, "Поставь музыку на паузу", Exp.DEVICE_TOOL,
            "Роутер секция 3: media.control PAUSE", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-024", DEVICE, "Проложи маршрут до Амстердама", Exp.DEVICE_TOOL,
            "Роутер секция 10: location.navigation", requiresDeviceControl = true))
        add(BenchmarkCase("DEVICE-025", DEVICE, "Какая модель у телефона", Exp.DEVICE_TOOL,
            "Роутер секция 14: system.device_info", requiresDeviceControl = true))

        // ================================================================
        // LOCAL_AI — 22. Знаниевые вопросы без актуальных данных и без устройства.
        // ================================================================
        add(BenchmarkCase("LOCAL-001", LOCAL_AI, "Что значит квантовая запутанность?", Exp.LOCAL_AI,
            "Общее знание, web не нужен, устройство не трогаем"))
        add(BenchmarkCase("LOCAL-002", LOCAL_AI, "Что такое NFT?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-003", LOCAL_AI, "Объясни принцип работы DNS", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-004", LOCAL_AI, "Что такое рекурсия?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-005", LOCAL_AI, "Объясни разницу между TCP и UDP", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-006", LOCAL_AI, "Что такое REST API?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-007", LOCAL_AI, "Почему небо голубое?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-008", LOCAL_AI, "Расскажи про теорию относительности", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-009", LOCAL_AI, "Как работает блокчейн?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-010", LOCAL_AI, "Что такое машинное обучение?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-011", LOCAL_AI, "Объясни что такое кэш процессора", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-012", LOCAL_AI, "В чём разница между HTTP и HTTPS?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-013", LOCAL_AI, "Что означает аббревиатура API?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-014", LOCAL_AI, "Расскажи анекдот", Exp.LOCAL_AI,
            "Генерация текста, сеть не нужна"))
        add(BenchmarkCase("LOCAL-015", LOCAL_AI, "Придумай название для кофейни", Exp.LOCAL_AI,
            "Генерация текста"))
        add(BenchmarkCase("LOCAL-016", LOCAL_AI, "Как приготовить омлет?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-017", LOCAL_AI, "Сколько будет 15 процентов от 240?", Exp.LOCAL_AI,
            "Арифметика, сеть не нужна"))
        add(BenchmarkCase("LOCAL-018", LOCAL_AI, "Дай совет как побороть прокрастинацию", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-019", LOCAL_AI, "Что такое чёрная дыра?", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-020", LOCAL_AI, "Объясни принцип работы холодильника", Exp.LOCAL_AI,
            "Общее знание"))
        add(BenchmarkCase("LOCAL-021", LOCAL_AI, "Напиши короткое поздравление с днём рождения", Exp.LOCAL_AI,
            "Генерация текста"))
        add(BenchmarkCase("LOCAL-022", LOCAL_AI, "Чем отличается вирус от бактерии?", Exp.LOCAL_AI,
            "Общее знание"))

        // ================================================================
        // CLOUD_AI — 15. Слишком сложно/длинно для 1B-модели, но web не нужен.
        // ================================================================
        add(BenchmarkCase("CLOUD-001", CLOUD_AI,
            "Проанализируй следующий договор аренды и выдели риски для арендатора: " +
                "договор заключается на 11 месяцев с автоматической пролонгацией, " +
                "депозит равен двум месячным платежам и не возвращается при досрочном " +
                "расторжении, индексация арендной платы производится ежеквартально " +
                "по усмотрению арендодателя без ограничения верхнего предела, " +
                "текущий ремонт полностью за счёт арендатора, а арендодатель вправе " +
                "расторгнуть договор в одностороннем порядке с уведомлением за 14 дней",
            Exp.CLOUD_AI, "Длинный юридический анализ — за пределами 1B-модели"))
        add(BenchmarkCase("CLOUD-002", CLOUD_AI,
            "Сделай подробное резюме этого технического отчёта: система показала " +
                "деградацию производительности на 40 процентов при нагрузке свыше " +
                "десяти тысяч одновременных соединений, основной причиной является " +
                "блокировка пула соединений с базой данных, вторичным фактором — " +
                "неоптимальные индексы на таблице событий, а также отсутствие " +
                "кэширования часто запрашиваемых агрегатов и синхронная запись логов",
            Exp.CLOUD_AI, "Длинный анализ — за пределами локальной модели"))
        add(BenchmarkCase("CLOUD-003", CLOUD_AI,
            "Напиши подробный бизнес-план для小 кофейни с расчётом окупаемости, " +
                "структурой затрат, анализом целевой аудитории, маркетинговой стратегией " +
                "и прогнозом выручки на три года вперёд с разбивкой по кварталам",
            Exp.CLOUD_AI, "Объёмная аналитическая задача"))
        add(BenchmarkCase("CLOUD-004", CLOUD_AI,
            "Проанализируй архитектуру микросервисов и предложи улучшения с учётом " +
                "отказоустойчивости, наблюдаемости, стоимости эксплуатации и сложности " +
                "миграции существующих данных между сервисами без простоя",
            Exp.CLOUD_AI, "Сложное рассуждение"))
        add(BenchmarkCase("CLOUD-005", CLOUD_AI,
            "Напиши код на Kotlin для реализации потокобезопасного LRU-кэша " +
                "с поддержкой TTL, ленивой инвалидацией и метриками попаданий",
            Exp.CLOUD_AI, "Генерация кода — качество 1B недостаточно"))
        add(BenchmarkCase("CLOUD-006", CLOUD_AI,
            "Сравни преимущества и недостатки монолитной и микросервисной архитектуры " +
                "для команды из пяти разработчиков с учётом стоимости поддержки, " +
                "скорости вывода функций и требований к отказоустойчивости",
            Exp.CLOUD_AI, "Развёрнутое сравнение"))
        add(BenchmarkCase("CLOUD-007", CLOUD_AI,
            "Проанализируй причины оттока клиентов и предложи стратегию удержания " +
                "с приоритизацией мер по соотношению стоимости и ожидаемого эффекта",
            Exp.CLOUD_AI, "Бизнес-аналитика"))
        add(BenchmarkCase("CLOUD-008", CLOUD_AI,
            "Объясни устройство трансформерной архитектуры нейросетей подробно, " +
                "включая механизм внимания, позиционное кодирование и нормализацию слоёв",
            Exp.CLOUD_AI, "Глубокое техническое объяснение"))
        add(BenchmarkCase("CLOUD-009", CLOUD_AI,
            "Составь подробную инвестиционную стратегию на пять лет с учётом " +
                "диверсификации, горизонта риска и налоговых последствий",
            Exp.CLOUD_AI, "Сложная аналитика"))
        add(BenchmarkCase("CLOUD-010", CLOUD_AI,
            "Проверь этот текст на логические ошибки и предложи улучшенную редакцию " +
                "с сохранением авторского стиля и структуры аргументации",
            Exp.CLOUD_AI, "Сложная работа с текстом"))
        add(BenchmarkCase("CLOUD-011", CLOUD_AI,
            "Разработай стратегию выхода продукта на европейский рынок с учётом " +
                "регуляторных требований, локализации и конкурентного окружения",
            Exp.CLOUD_AI, "Стратегическая задача"))
        add(BenchmarkCase("CLOUD-012", CLOUD_AI,
            "Напиши подробную техническую документацию для REST API системы платежей " +
                "с описанием методов, кодов ошибок, идемпотентности и примерами",
            Exp.CLOUD_AI, "Объёмная генерация"))
        add(BenchmarkCase("CLOUD-013", CLOUD_AI,
            "Проанализируй финансовую отчётность компании и оцени риски " +
                "с точки зрения ликвидности, долговой нагрузки и качества выручки",
            Exp.CLOUD_AI, "Финансовая аналитика"))
        add(BenchmarkCase("CLOUD-014", CLOUD_AI,
            "Составь подробный учебный план по изучению Kotlin с нуля до продвинутого " +
                "уровня на шесть месяцев с еженедельными целями и проектами",
            Exp.CLOUD_AI, "Объёмное планирование"))
        add(BenchmarkCase("CLOUD-015", CLOUD_AI,
            "Объясни различия между реляционными и графовыми базами данных подробно, " +
                "с примерами запросов, моделей данных и сценариев применения",
            Exp.CLOUD_AI, "Развёрнутое техническое сравнение"))

        // ================================================================
        // CLOUD_WEB — 10. Нужны актуальные данные. Local AI НЕ должен
        // притворяться, что знает их.
        // ================================================================
        add(BenchmarkCase("WEB-001", CLOUD_WEB, "Какие новости сегодня?", Exp.CLOUD_AI,
            "Актуальные данные, requiresWeb", requiresWeb = true))
        add(BenchmarkCase("WEB-002", CLOUD_WEB, "Какая сегодня цена биткоина?", Exp.CLOUD_AI,
            "Актуальные котировки", requiresWeb = true))
        add(BenchmarkCase("WEB-003", CLOUD_WEB, "Найди актуальную цену iPhone 15", Exp.CLOUD_AI,
            "Актуальные цены", requiresWeb = true))
        add(BenchmarkCase("WEB-004", CLOUD_WEB, "Какой сейчас курс евро к доллару?", Exp.CLOUD_AI,
            "Актуальный курс", requiresWeb = true))
        add(BenchmarkCase("WEB-005", CLOUD_WEB, "Что произошло в мире за последнюю неделю?", Exp.CLOUD_AI,
            "Актуальные события", requiresWeb = true))
        add(BenchmarkCase("WEB-006", CLOUD_WEB, "Найди отзывы о новом ноутбуке Lenovo", Exp.CLOUD_AI,
            "Поиск в сети", requiresWeb = true))
        add(BenchmarkCase("WEB-007", CLOUD_WEB, "Когда следующий матч сборной?", Exp.CLOUD_AI,
            "Актуальное расписание", requiresWeb = true))
        add(BenchmarkCase("WEB-008", CLOUD_WEB, "Какие сейчас цены на авиабилеты в Стамбул?", Exp.CLOUD_AI,
            "Актуальные цены", requiresWeb = true))
        add(BenchmarkCase("WEB-009", CLOUD_WEB, "Найди информацию о последнем релизе Kotlin", Exp.CLOUD_AI,
            "Актуальная информация", requiresWeb = true))
        add(BenchmarkCase("WEB-010", CLOUD_WEB, "Какой курс акций Apple сейчас?", Exp.CLOUD_AI,
            "Актуальные котировки", requiresWeb = true))

        // ================================================================
        // AGENT — 10. Многошаговые сценарии.
        // ================================================================
        add(BenchmarkCase("AGENT-001", AGENT, "Подготовь телефон ко сну", Exp.AGENT,
            "ScenarioMatcher: SLEEP → многошаговый план"))
        add(BenchmarkCase("AGENT-002", AGENT, "Я ухожу из дома", Exp.AGENT,
            "ScenarioMatcher: LEAVING_HOME"))
        add(BenchmarkCase("AGENT-003", AGENT, "Я пришёл домой", Exp.AGENT,
            "ScenarioMatcher: COMING_HOME"))
        add(BenchmarkCase("AGENT-004", AGENT, "Включи ночной режим", Exp.AGENT,
            "ScenarioMatcher: SLEEP"))
        add(BenchmarkCase("AGENT-005", AGENT, "Режим совещания", Exp.AGENT,
            "ScenarioMatcher: MEETING"))
        add(BenchmarkCase("AGENT-006", AGENT, "Подготовь к поездке за рулём", Exp.AGENT,
            "ScenarioMatcher: DRIVING"))
        add(BenchmarkCase("AGENT-007", AGENT, "Включи режим экономии заряда", Exp.AGENT,
            "ScenarioMatcher: POWER_SAVING"))
        add(BenchmarkCase("AGENT-008", AGENT, "Проведи диагностику системы", Exp.AGENT,
            "ScenarioMatcher: DIAGNOSTICS"))
        add(BenchmarkCase("AGENT-009", AGENT,
            "Сделай план поездки в Италию на 7 дней и сравни варианты перелёта",
            Exp.AGENT, "Многошаговая задача с несколькими подзадачами",
            requiresWeb = true,
            knownGap = "ScenarioMatcher покрывает только 10 предопределённых сценариев; " +
                "произвольное многошаговое планирование без LLM-плана не распознаётся"))
        add(BenchmarkCase("AGENT-010", AGENT,
            "Сравни десять вариантов ноутбуков и выбери лучший в пределах бюджета",
            Exp.AGENT, "Многошаговое сравнение",
            requiresWeb = true,
            knownGap = "Требует LLM-плана; локальный ScenarioMatcher такое не строит"))

        // ================================================================
        // AMBIGUOUS — 6. Не хватает контекста → корректно просить уточнение.
        // ================================================================
        add(BenchmarkCase("AMB-001", AMBIGUOUS, "Открой", Exp.CLARIFICATION,
            "Нет объекта действия — нужно уточнение, а не случайное приложение"))
        add(BenchmarkCase("AMB-002", AMBIGUOUS, "Найди это", Exp.CLARIFICATION,
            "Референт неизвестен"))
        add(BenchmarkCase("AMB-003", AMBIGUOUS, "Что лучше?", Exp.CLARIFICATION,
            "Нет предмета сравнения"))
        add(BenchmarkCase("AMB-004", AMBIGUOUS, "Проверь", Exp.CLARIFICATION,
            "Нет объекта проверки"))
        add(BenchmarkCase("AMB-005", AMBIGUOUS, "Переведи", Exp.CLARIFICATION,
            "Нет текста для перевода"))
        add(BenchmarkCase("AMB-006", AMBIGUOUS, "Сделай это", Exp.CLARIFICATION,
            "Референт неизвестен"))

        // ================================================================
        // EDGE_CASE — 7.
        // ================================================================
        add(BenchmarkCase("EDGE-001", EDGE_CASE, "", Exp.REFUSAL,
            "Пустой запрос обязан быть отклонён", expectedSuccess = false))
        add(BenchmarkCase("EDGE-002", EDGE_CASE, "открой телегу", Exp.DEVICE_TOOL,
            "Разговорная форма Telegram — роутер знает 'телегу'", requiresDeviceControl = true))
        add(BenchmarkCase("EDGE-003", EDGE_CASE, "включи блютуз", Exp.DEVICE_TOOL,
            "Разговорная форма + строчные буквы", requiresDeviceControl = true))
        add(BenchmarkCase("EDGE-004", EDGE_CASE, "what is quantum entanglement", Exp.ANY_NON_DEVICE,
            "Английский ввод: допустимо local или cloud, но не device-действие"))
        add(BenchmarkCase("EDGE-005", EDGE_CASE, "постав таймер на десять мин", Exp.ANY_NON_DEVICE,
            "Опечатка + разговорная форма. Тул alarm_timer есть, но роутер его не вызывает",
            knownGap = "FastCommandRouter не имеет секции для productivity.alarm_timer — " +
                "команда уйдёт в AI вместо установки таймера"))
        add(BenchmarkCase("EDGE-006", EDGE_CASE, "а" .repeat(2500), Exp.CLOUD_AI,
            "Очень длинный ввод: локальная модель обязана отказаться (лимит 1200 символов)"))
        add(BenchmarkCase("EDGE-007", EDGE_CASE, "Сделай мне бутерброд", Exp.ANY_NON_DEVICE,
            "Физически невыполнимая команда — ассистент должен ответить, а не действовать"))

        // ================================================================
        // PRIVACY — 5. Приватность соблюдается на обоих уровнях.
        // ================================================================
        add(BenchmarkCase("PRIV-001", PRIVACY,
            "Запомни мой пароль от банковского приложения", Exp.LOCAL_AI,
            "PRIVATE обрабатывается локально, в облако не уходит",
            privacyLevel = PrivacyLevel.PRIVATE))
        add(BenchmarkCase("PRIV-002", PRIVACY,
            "Что я записывал о своём здоровье?", Exp.LOCAL_AI,
            "PRIVATE — только локально", privacyLevel = PrivacyLevel.PRIVATE))
        add(BenchmarkCase("PRIV-003", PRIVACY,
            "Проанализируй мою медицинскую карту", Exp.LOCAL_AI,
            "SENSITIVE — локально либо отказ, но НЕ облако",
            privacyLevel = PrivacyLevel.SENSITIVE))
        add(BenchmarkCase("PRIV-004", PRIVACY,
            "Мои личные заметки о финансах", Exp.LOCAL_AI,
            "SENSITIVE — не в облако", privacyLevel = PrivacyLevel.SENSITIVE))
        add(BenchmarkCase("PRIV-005", PRIVACY,
            "Найди в интернете информацию о моём диагнозе", Exp.REFUSAL,
            "SENSITIVE + requiresWeb: локально нельзя, в облако нельзя → честный отказ",
            requiresWeb = true, privacyLevel = PrivacyLevel.SENSITIVE,
            expectedSuccess = false))
    }

    /** Распределение по категориям — для отчёта. */
    fun distribution(): Map<BenchmarkCategory, Int> =
        cases.groupingBy { it.category }.eachCount()

    /** Проверка целостности набора. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val ids = cases.map { it.id }
        if (ids.size != ids.toSet().size) problems += "duplicate case ids"
        if (cases.size < 50) problems += "dataset must contain at least 50 cases"
        cases.filter { it.rationale.isBlank() }.forEach {
            problems += "${it.id}: missing rationale"
        }
        return problems
    }
}
