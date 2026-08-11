package com.jarvis.assistant.agent.automation.engine

import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.automation.model.TimeRangeCondition
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal Automation Engine (v0.6)
 * Обрабатывает события ОС и выполняет цепочки действий:
 * Trigger ──► Condition Check (Время/Батарея) ──► Workflow Actions ──► Ear Voice Feedback
 */
@Singleton
class PersonalAutomationEngine @Inject constructor(
    private val automationDao: AutomationDao,
    private val toolExecutor: ToolExecutor,
    private val textToSpeechManager: TextToSpeechManager,
    private val json: Json
) {
    /**
     * Обработка системного события (например: подключение наушников, смена Wi-Fi, падение батареи)
     */
    suspend fun onSystemEvent(triggerType: AutomationTriggerType, extraData: Map<String, Any> = emptyMap()) = withContext(Dispatchers.IO) {
        initDefaultAutomationsIfNeeded()

        val candidateRules = automationDao.getAutomationsByTrigger(triggerType.name)
        if (candidateRules.isEmpty()) return@withContext

        val now = Calendar.getInstance(Locale.getDefault())
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        for (rule in candidateRules) {
            // 1. Проверка предусловий (Conditions: Time Window)
            if (!isConditionSatisfied(rule.conditionsJson, currentHour, currentMinute)) {
                continue
            }

            // 2. Парсинг и последовательное выполнение действий инструмента
            val calls = parseActionCalls(rule.actionsJson)
            if (calls.isNotEmpty()) {
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
                break // Одно правило на событие
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

            currentTotalMinutes in startTotalMinutes..endTotalMinutes
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

    private suspend fun initDefaultAutomationsIfNeeded() {
        val existing = automationDao.getActiveAutomations()
        if (existing.isNotEmpty()) return

        // 🎧 Правило 1: "Утренний режим в наушниках" (06:00 - 12:00)
        val morningActions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.volume")
                putJsonObject("arguments") {
                    put("action", "set")
                    put("percent", 50)
                }
            })
            add(buildJsonObject {
                put("tool", "device.open_app")
                putJsonObject("arguments") {
                    put("app_name", "telegram")
                }
            })
        }.toString()

        val morningTimeCondition = json.encodeToString(
            TimeRangeCondition.serializer(),
            TimeRangeCondition(startHour = 6, startMinute = 0, endHour = 12, endMinute = 0)
        )

        automationDao.insertAutomation(
            AutomationEntity(
                ruleId = "morning_headphones_routine",
                name = "Утренний режим в наушниках",
                triggerType = AutomationTriggerType.HEADPHONES_CONNECTED.name,
                conditionsJson = morningTimeCondition,
                actionsJson = morningActions,
                voiceAnnouncement = "Доброе утро, сэр. Наушники подключены: открываю Telegram и устанавливаю громкость 50%."
            )
        )

        // 🏠 Правило 2: "Возвращение домой (Wi-Fi подключен)"
        val homeActions = buildJsonArray {
            add(buildJsonObject {
                put("tool", "device.volume")
                putJsonObject("arguments") {
                    put("action", "set")
                    put("percent", 70)
                }
            })
        }.toString()

        automationDao.insertAutomation(
            AutomationEntity(
                ruleId = "home_wifi_arrival",
                name = "Домашний режим",
                triggerType = AutomationTriggerType.WIFI_CONNECTED.name,
                actionsJson = homeActions,
                voiceAnnouncement = "С возвращением домой, сэр. Громкость установлена на 70%."
            )
        )
    }

    /**
     * Создание пользовательского правила автоматизации (например: голосом или из настроек)
     */
    suspend fun createAutomationRule(
        name: String,
        triggerType: AutomationTriggerType,
        timeCondition: TimeRangeCondition? = null,
        actions: List<ToolCall>,
        voiceAnnouncement: String = ""
    ) = withContext(Dispatchers.IO) {
        val ruleId = UUID.randomUUID().toString()
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

        automationDao.insertAutomation(
            AutomationEntity(
                ruleId = ruleId,
                name = name,
                triggerType = triggerType.name,
                conditionsJson = conditionsJson,
                actionsJson = actionsArray,
                voiceAnnouncement = voiceAnnouncement,
                isEnabled = true
            )
        )
    }
}
