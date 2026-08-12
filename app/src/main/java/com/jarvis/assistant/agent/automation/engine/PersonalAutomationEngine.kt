package com.jarvis.assistant.agent.automation.engine

import android.util.Log
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.automation.model.TimeRangeCondition
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.voice.tts.TextToSpeechManager
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
    private val json: Json
) {
    companion object {
        private const val TAG = "AutomationEngine"
        private const val PREFS_NAME = "jarvis_automation_prefs"
        private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized"
    }

    private val initMutex = Mutex()
    private var defaultsInitialized = false

    /**
     * Обработка системного события (например: подключение наушников, смена Wi-Fi, падение батареи)
     */
    suspend fun onSystemEvent(
        triggerType: AutomationTriggerType, 
        extraData: Map<String, Any> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, ">>> Event received: ${triggerType.name}")

        // Инициализируем дефолтные правила если нужно
        ensureDefaultsInitialized()

        val candidateRules = automationDao.getAutomationsByTrigger(triggerType.name)
        Log.d(TAG, "Found ${candidateRules.size} rules for ${triggerType.name}")
        
        if (candidateRules.isEmpty()) return@withContext

        val now = Calendar.getInstance(Locale.getDefault())
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        for (rule in candidateRules) {
            if (!rule.isEnabled) {
                Log.d(TAG, "Rule '${rule.name}' is disabled, skipping")
                continue
            }

            // 1. Проверка предусловий (Conditions: Time Window)
            if (!isConditionSatisfied(rule.conditionsJson, currentHour, currentMinute)) {
                Log.d(TAG, "Rule '${rule.name}' skipped: time condition not satisfied")
                continue
            }

            // 2. Парсинг и последовательное выполнение действий инструмента
            val calls = parseActionCalls(rule.actionsJson)
            if (calls.isNotEmpty()) {
                Log.d(TAG, "Executing ${calls.size} actions for rule '${rule.name}'")
                
                try {
                    val results = toolExecutor.executeAll(calls)
                    automationDao.recordTrigger(rule.id)

                    // 3. Голосовое оповещение прямо в наушник
                    val voiceFeedback = if (rule.voiceAnnouncement.isNotBlank()) {
                        rule.voiceAnnouncement
                    } else {
                        val summary = results.filter { it.isSuccess }.joinToString(". ") { it.summary }
                        if (summary.isNotBlank()) "$summary, сэр." else ""
                    }

                    if (voiceFeedback.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            textToSpeechManager.speak(voiceFeedback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing rule '${rule.name}'", e)
                }
                
                break // Одно правило на событие
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
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default automations", e)
            }
        }
    }

    private fun isConditionSatisfied(conditionsJson: String, currentHour: Int, currentMinute: Int): Boolean {
        if (conditionsJson.isBlank()) return true
        return try {
            val cond = json.decodeFromString<TimeRangeCondition>(conditionsJson)
            val currentTotalMinutes = currentHour * 60 + currentMinute
            val startTotalMinutes = cond.startHour * 60 + cond.startMinute
            val endTotalMinutes = cond.endHour * 60 + cond.endMinute

            // Обработка перехода через полночь
            if (startTotalMinutes <= endTotalMinutes) {
                currentTotalMinutes in startTotalMinutes..endTotalMinutes
            } else {
                currentTotalMinutes >= startTotalMinutes || currentTotalMinutes <= endTotalMinutes
            }
        } catch (_: Exception) {
            true
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
            createHeadphonesDisconnectedRule()
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
            isEnabled = true
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
            isEnabled = true
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
            isEnabled = true
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
        voiceAnnouncement: String = ""
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
            conditionsJson = conditionsJson,
            actionsJson = actionsArray,
            voiceAnnouncement = voiceAnnouncement,
            isEnabled = true
        )

        automationDao.insertAutomation(entity)
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
    }

    /**
     * Удаление правила
     */
    suspend fun deleteRule(ruleId: String) = withContext(Dispatchers.IO) {
        automationDao.deleteAutomationByRuleId(ruleId)
    }
}
