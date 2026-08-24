package com.jarvis.assistant.agent.automation.engine

import android.util.Log
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.automation.model.TimeRangeCondition
import com.jarvis.assistant.agent.automation.scheduler.AutomationScheduleManager
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal Automation Engine v2.0
 * 
 * Обрабатывает события ОС и выполняет цепочки действий:
 * Trigger ──► Condition Check (Время/Батарея) ──► Workflow Actions ──► Ear Voice Feedback
 * 
 * Исправления v2.0:
 * - Корректная инициализация дефолтных правил
 * - Mutex для thread-safety
 * - Улучшенная обработка ошибок
 */
@Singleton
class PersonalAutomationEngine @Inject constructor(
    private val automationDao: AutomationDao,
    private val toolExecutor: ToolExecutor,
    private val textToSpeechManager: TextToSpeechManager,
    private val ruleMatcher: AutomationRuleMatcher,
    private val scheduleManager: AutomationScheduleManager,
    private val json: Json
) {
    companion object {
        private const val TAG = "AutomationEngine"

        /** Защита от повторных срабатываний при «дребезге» системных событий. */
        private const val DEFAULT_COOLDOWN_MS = 60_000L
    }

    private val initMutex = Mutex()
    private val eventMutex = Mutex()

    @Volatile
    private var defaultsInitialized = false

    /**
     * Обработка системного события (например: подключение наушников, смена Wi-Fi, падение батареи)
     */
    suspend fun onSystemEvent(
        triggerType: AutomationTriggerType
    ) = withContext(Dispatchers.IO) {
        // Матчинг по lastTriggeredAt и запись recordTrigger должны быть одной
        // критической секцией. Иначе два одинаковых broadcast одновременно
        // проходят cooldown и выполняют необратимое действие дважды.
        eventMutex.withLock {
            Log.d(TAG, ">>> Event received: ${triggerType.name}")

            ensureDefaultsInitialized()

            val candidateRules = automationDao.getAutomationsByTrigger(triggerType.name)
            Log.d(TAG, "Found ${candidateRules.size} rules for ${triggerType.name}")

            if (candidateRules.isNotEmpty()) {
                val now = Calendar.getInstance(Locale.getDefault())
                val nowMillis = System.currentTimeMillis()
                val currentHour = now.get(Calendar.HOUR_OF_DAY)
                val currentMinute = now.get(Calendar.MINUTE)

                val matchingRules = ruleMatcher.matchForTrigger(
                    candidateRules,
                    nowMillis,
                    currentHour,
                    currentMinute
                )
                Log.d(TAG, "Matched ${matchingRules.size} rules for ${triggerType.name}")
                executeRules(matchingRules, nowMillis)
            }
        }
    }

    /**
     * Обработка расписания: точное время (TIME_SCHEDULE, triggerParam "HH:MM").
     *
     * Пример: 07:00 → открыть календарь, сказать погоду, прочитать расписание,
     * сообщить важные задачи — ВСЕ правила на 07:00 выполняются.
     */
    suspend fun onTimeSchedule(hour: Int, minute: Int) = withContext(Dispatchers.IO) {
        eventMutex.withLock {
            Log.d(TAG, ">>> Schedule event: %02d:%02d".format(hour, minute))

            ensureDefaultsInitialized()

            val candidateRules = automationDao.getAutomationsByTrigger(AutomationTriggerType.TIME_SCHEDULE.name)
            if (candidateRules.isNotEmpty()) {
                val nowMillis = System.currentTimeMillis()
                val matchingRules = ruleMatcher.matchForSchedule(candidateRules, nowMillis, hour, minute)
                Log.d(TAG, "Matched ${matchingRules.size} rules for schedule %02d:%02d".format(hour, minute))
                executeRules(matchingRules, nowMillis)
            }
        }
    }

    /**
     * Выполняет ВСЕ переданные правила по порядку (приоритет уже учтён
     * RuleMatcher'ом). Сбой одного правила не останавливает остальные.
     */
    private suspend fun executeRules(rules: List<AutomationEntity>, nowMillis: Long) {
        if (rules.isEmpty()) return

        val voiceMessages = mutableListOf<String>()

        for (rule in rules) {
            val calls = parseActionCalls(rule.actionsJson)
            if (calls.isEmpty()) {
                Log.w(TAG, "Rule id=${rule.id} has no parsable actions")
                continue
            }

            Log.d(TAG, "Executing ${calls.size} actions for rule id=${rule.id}")
            try {
                val results = toolExecutor.executeAll(calls)
                val completedSuccessfully =
                    results.size == calls.size && results.all { it.isSuccess }

                if (completedSuccessfully) {
                    automationDao.recordTrigger(rule.id, nowMillis)
                    val voiceFeedback = if (rule.voiceAnnouncement.isNotBlank()) {
                        rule.voiceAnnouncement
                    } else {
                        results.joinToString(". ") { it.summary }
                            .takeIf { it.isNotBlank() }
                            ?.let { "$it, сэр." }
                            .orEmpty()
                    }
                    if (voiceFeedback.isNotBlank()) voiceMessages.add(voiceFeedback)
                } else {
                    // Не записываем cooldown и не произносим ложное «выполнено»:
                    // executeAll мог вернуть FAILURE/PERMISSION_REQUIRED/
                    // REQUIRES_USER_CONFIRMATION после частичного rollback.
                    val failed = results.lastOrNull()
                    Log.w(
                        TAG,
                        "Rule id=${rule.id} not completed: status=${failed?.status}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой одного правила не должен останавливать остальные правила события.
                Log.e(
                    TAG,
                    "Error executing rule id=${rule.id}, type=${e.javaClass.simpleName}; continuing"
                )
            }
        }

        if (voiceMessages.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                textToSpeechManager.speak(voiceMessages.joinToString(" "))
            }
        }
    }

    /**
     * Гарантирует инициализацию дефолтных правил (thread-safe, один раз)
     */
    private suspend fun ensureDefaultsInitialized() {
        if (defaultsInitialized) return
        
        initMutex.withLock {
            if (defaultsInitialized) return@withLock
            
            try {
                initDefaultAutomations()
                defaultsInitialized = true
                scheduleManager.reconcile()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default automations", e)
            }
        }
    }

    private fun parseActionCalls(actionsJson: String): List<ToolCall> {
        return try {
            val element = json.parseToJsonElement(actionsJson).jsonArray
            element.mapNotNull { item ->
                val obj = item.jsonObject
                val toolId = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val args = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                ToolCall(toolId = toolId, arguments = args)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Инициализация дефолтных правил автоматизации.
     * Теперь проверяет каждое правило отдельно и добавляет только отсутствующие.
     */
    private suspend fun initDefaultAutomations() {
        val defaultRules = listOf(
            createMorningHeadphonesRule(),
            createHomeWifiRule(),
            createBatteryLowRule(),
            createHeadphonesDisconnectedRule(),
            createMorningScheduleRule()
        )

        for (rule in defaultRules) {
            try {
                // Проверяем, существует ли правило с таким ruleId
                val existing = automationDao.getAutomationByRuleId(rule.ruleId)
                if (existing == null) {
                    automationDao.insertAutomation(rule)
                    Log.d(TAG, "Inserted default rule: ${rule.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting rule ${rule.ruleId}", e)
            }
        }
    }

    /**
     * Дефолтное правило 07:00 — пример «одно событие → много действий»:
     *   открыть календарь, сказать погоду, прочитать расписание, сообщить задачи.
     */
    private fun createMorningScheduleRule(): AutomationEntity {
        val actions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.open_app")
                putJsonObject("arguments") {
                    put("app_name", "calendar")
                }
            })
            add(buildJsonObject {
                put("tool", "intelligence.weather")
                putJsonObject("arguments") { }
            })
            add(buildJsonObject {
                put("tool", "system.time")
                putJsonObject("arguments") { }
            })
            add(buildJsonObject {
                put("tool", "memory.recall")
                putJsonObject("arguments") {
                    put("query", "важные задачи")
                }
            })
        }.toString()

        return AutomationEntity(
            ruleId = "default_morning_schedule",
            name = "Утреннее расписание 07:00",
            triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
            triggerParam = "07:00",
            conditionsJson = "",
            actionsJson = actions,
            voiceAnnouncement = "Доброе утро, сэр. Вот ваш план на день: календарь, погода, расписание и задачи.",
            isEnabled = true,
            priority = 30,
            // Точное время — срабатываем раз в сутки, а не каждую минуту.
            cooldownMs = 24L * 60 * 60 * 1000
        )
    }

    private fun createMorningHeadphonesRule(): AutomationEntity {
        val actions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.volume")
                putJsonObject("arguments") {
                    put("action", "set")
                    put("percent", 50)
                }
            })
        }.toString()

        val timeCondition = json.encodeToString(
            TimeRangeCondition.serializer(),
            TimeRangeCondition(startHour = 6, startMinute = 0, endHour = 12, endMinute = 0)
        )

        return AutomationEntity(
            ruleId = "default_morning_headphones",
            name = "Утренний режим в наушниках",
            triggerType = AutomationTriggerType.HEADPHONES_CONNECTED.name,
            conditionsJson = timeCondition,
            actionsJson = actions,
            voiceAnnouncement = "Доброе утро, сэр. Громкость установлена на 50%.",
            isEnabled = true,
            priority = 10,
            cooldownMs = DEFAULT_COOLDOWN_MS
        )
    }

    private fun createHomeWifiRule(): AutomationEntity {
        val actions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.volume")
                putJsonObject("arguments") {
                    put("action", "set")
                    put("percent", 70)
                }
            })
        }.toString()

        return AutomationEntity(
            ruleId = "default_home_wifi",
            name = "Домашний режим",
            triggerType = AutomationTriggerType.WIFI_CONNECTED.name,
            conditionsJson = "",
            actionsJson = actions,
            voiceAnnouncement = "С возвращением домой, сэр. Громкость установлена на 70%.",
            isEnabled = true,
            priority = 5,
            cooldownMs = DEFAULT_COOLDOWN_MS
        )
    }

    private fun createBatteryLowRule(): AutomationEntity {
        val actions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.volume")
                putJsonObject("arguments") {
                    put("action", "set")
                    put("percent", 30)
                }
            })
            add(buildJsonObject {
                put("tool", "device.brightness")
                putJsonObject("arguments") {
                    put("percent", 20)
                }
            })
        }.toString()

        return AutomationEntity(
            ruleId = "default_battery_low",
            name = "Режим экономии батареи",
            triggerType = AutomationTriggerType.BATTERY_LOW.name,
            conditionsJson = "",
            actionsJson = actions,
            voiceAnnouncement = "Внимание, сэр. Низкий заряд батареи. Включаю режим экономии.",
            isEnabled = true,
            priority = 20,
            cooldownMs = DEFAULT_COOLDOWN_MS
        )
    }

    private fun createHeadphonesDisconnectedRule(): AutomationEntity {
        val actions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "media.control")
                putJsonObject("arguments") {
                    put("action", "pause")
                }
            })
        }.toString()

        return AutomationEntity(
            ruleId = "default_headphones_disconnected",
            name = "Пауза при отключении наушников",
            triggerType = AutomationTriggerType.HEADPHONES_DISCONNECTED.name,
            conditionsJson = "",
            actionsJson = actions,
            voiceAnnouncement = "",
            isEnabled = true
        )
    }

    /**
     * Создание пользовательского правила автоматизации
     */
    suspend fun createAutomationRule(
        name: String,
        triggerType: AutomationTriggerType,
        timeCondition: TimeRangeCondition? = null,
        actions: List<ToolCall>,
        voiceAnnouncement: String = "",
        priority: Int = 0,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        triggerParam: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val ruleId = "user_${UUID.randomUUID()}"
        
        val actionsArray = buildJsonArray {
            actions.forEach { call ->
                add(buildJsonObject {
                    put("tool", call.toolId)
                    put("arguments", call.arguments)
                })
            }
        }.toString()

        val conditionsJson = if (timeCondition != null) {
            json.encodeToString(TimeRangeCondition.serializer(), timeCondition)
        } else ""

        val entity = AutomationEntity(
            ruleId = ruleId,
            name = name,
            triggerType = triggerType.name,
            triggerParam = triggerParam,
            conditionsJson = conditionsJson,
            actionsJson = actionsArray,
            voiceAnnouncement = voiceAnnouncement,
            isEnabled = true,
            priority = priority,
            cooldownMs = cooldownMs
        )

        val id = automationDao.insertAutomation(entity)
        if (triggerType == AutomationTriggerType.TIME_SCHEDULE) scheduleManager.reconcile()
        id
    }

    /**
     * Получение всех активных правил
     */
    suspend fun getActiveRules(): List<AutomationEntity> = withContext(Dispatchers.IO) {
        ensureDefaultsInitialized()
        automationDao.getActiveAutomations()
    }

    /**
     * Включение/выключение правила
     */
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        automationDao.setAutomationEnabled(ruleId, enabled)
        scheduleManager.reconcile()
    }

    /**
     * Удаление правила
     */
    suspend fun deleteRule(ruleId: String) = withContext(Dispatchers.IO) {
        automationDao.deleteAutomationByRuleId(ruleId)
        scheduleManager.reconcile()
    }
}
