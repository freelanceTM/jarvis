package com.jarvis.assistant.agent.memory.procedural

import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.memory.dao.ProcedureDao
import com.jarvis.assistant.agent.memory.entity.ProcedureEntity
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowExecutor @Inject constructor(
    private val procedureDao: ProcedureDao,
    private val toolExecutor: ToolExecutor,
    private val json: Json
) {
    /**
     * Проверяет, является ли команда сохраненным процедурным макросом (например: "сон", "работа", "тренировка")
     */
    suspend fun tryExecuteWorkflow(trigger: String): ToolExecutionResult? = withContext(Dispatchers.IO) {
        val cleanTrigger = trigger.lowercase().trim()
            .replace(Regex("^(джарвис|jarvis|жарвис)[,\\s]*"), "")
            .trim()

        var workflow = procedureDao.getProcedureByTrigger(cleanTrigger)

        if (workflow == null) {
            when {
                cleanTrigger == "сон" || cleanTrigger == "я спать" || cleanTrigger == "спокойной ночи" || cleanTrigger == "я пошел спать" -> {
                    registerDefaultWorkflows()
                    workflow = procedureDao.getProcedureByTrigger("сон")
                }
                cleanTrigger == "работа" || cleanTrigger == "рабочий режим" -> {
                    registerDefaultWorkflows()
                    workflow = procedureDao.getProcedureByTrigger("работа")
                }
            }
        }

        if (workflow == null) return@withContext null

        try {
            val jsonArray = json.parseToJsonElement(workflow.actionsJson).jsonArray
            val calls = mutableListOf<ToolCall>()

            for (elem in jsonArray) {
                val obj = elem.jsonObject
                val toolName = obj["tool"]?.jsonPrimitive?.content ?: continue
                val args = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                calls.add(ToolCall(toolId = toolName, arguments = args))
            }

            if (calls.isEmpty()) return@withContext null

            // Выполняем все действия сценария последовательно с транзакционным откатом
            val results = toolExecutor.executeAll(calls)
            procedureDao.recordExecution(workflow.triggerPhrase)

            val summary = results.joinToString(". ") { it.summary }
            return@withContext ToolExecutionResult.success("Сценарий '${workflow.triggerPhrase}' выполнен: $summary")
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.failure("Сбой выполнения сценария $cleanTrigger", e.localizedMessage ?: "Unknown error")
        }
    }

    private suspend fun registerDefaultWorkflows() {
        val sleepActions = listOf(
            ToolCall(toolId = "device.volume", arguments = buildJsonObject { put("action", "set"); put("percent", 10) }),
            ToolCall(toolId = "device.flashlight", arguments = buildJsonObject { put("enabled", false) })
        )
        registerWorkflow("сон", sleepActions)

        val workActions = listOf(
            ToolCall(toolId = "device.open_app", arguments = buildJsonObject { put("app_name", "telegram") }),
            ToolCall(toolId = "device.volume", arguments = buildJsonObject { put("action", "set"); put("percent", 50) })
        )
        registerWorkflow("работа", workActions)
    }

    suspend fun registerWorkflow(
        trigger: String,
        actions: List<ToolCall>
    ) = withContext(Dispatchers.IO) {
        val actionsJson = buildString {
            append("[")
            actions.forEachIndexed { idx, call ->
                append("{\"tool\":\"${call.toolId}\",\"arguments\":${call.arguments}}")
                if (idx < actions.size - 1) append(",")
            }
            append("]")
        }

        procedureDao.insertProcedure(
            ProcedureEntity(
                triggerPhrase = trigger.trim().lowercase(),
                actionsJson = actionsJson,
                executionCount = 0,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
