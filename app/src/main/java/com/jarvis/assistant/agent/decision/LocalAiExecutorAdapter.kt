package com.jarvis.assistant.agent.decision

import android.util.Log
import com.jarvis.assistant.agent.localai.LocalAi
import com.jarvis.assistant.agent.localai.LocalAiResult
import com.jarvis.assistant.agent.memory.procedural.WorkflowExecutor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local AI как execution backend движка решений (Этап 2).
 *
 * Реализует СУЩЕСТВУЮЩИЙ порт [LocalAiExecutor] — сам
 * [ExecutionDecisionEngine] не изменён ни на строку.
 *
 * Порядок внутри локального слоя:
 *
 * ```
 * 1. Процедурная память (WorkflowExecutor) — детерминированные офлайн-макросы
 *    пользователя («сон», «работа»). Мгновенно, без инференса.
 * 2. Локальная LLM (LocalAi) — свободный вопрос-ответ офлайн.
 * ```
 *
 * Маппинг исходов в контракт Этапа 1:
 *
 * ```
 * LocalAiResult.Success     → LocalAiOutcome.Handled   → ExecutionType.LOCAL_AI
 * LocalAiResult.Unsupported → LocalAiOutcome.Uncertain → Cloud AI / Agent
 * LocalAiResult.Error       → LocalAiOutcome.Failed    → ExecutionResult.Error
 * ```
 *
 * Почему `Error` НЕ эскалируется в облако: движок Этапа 1 намеренно
 * детерминирован (пункт 16 ТЗ) — один проход по цепочке без повторов.
 * «Модель сломалась» — это честная ошибка, а не повод молча отправить,
 * возможно приватный, запрос в сеть. Ситуация «модель просто не установлена»
 * возвращается как `Unsupported` и корректно уходит в облако.
 */
@Singleton
class CompositeLocalAiExecutor @Inject constructor(
    private val workflowExecutor: WorkflowExecutor,
    private val localAi: LocalAi
) : LocalAiExecutor {

    private companion object {
        const val TAG = "DecisionEngine"
    }

    /**
     * Локальный слой офлайн: ни процедурная память, ни on-device модель
     * не имеют доступа в интернет. Движок использует это, чтобы пропустить
     * локальный путь при `requiresWeb == true`.
     */
    override val hasWebCapability: Boolean = false

    override suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome {
        // ---------------------------------------------- 1. Процедурная память
        val workflowResult = workflowExecutor.tryExecuteWorkflow(request.text)
        if (workflowResult != null) {
            Log.d(TAG, "local layer = PROCEDURAL_MEMORY")
            return if (workflowResult.isSuccess) {
                LocalAiOutcome.Handled("${workflowResult.summary}, сэр.")
            } else {
                LocalAiOutcome.Failed(workflowResult.summary)
            }
        }

        // ------------------------------------------------- 2. Локальная модель
        return when (val result = localAi.execute(request)) {
            is LocalAiResult.Success -> {
                Log.d(TAG, "local layer = ON_DEVICE_LLM | ${result.metrics.toLogString()}")
                LocalAiOutcome.Handled(result.text)
            }

            is LocalAiResult.Unsupported -> {
                Log.d(TAG, "local layer declined: ${result.reason}")
                LocalAiOutcome.Uncertain
            }

            is LocalAiResult.Error -> {
                Log.w(TAG, "local layer error: ${result.message}")
                LocalAiOutcome.Failed(result.message)
            }
        }
    }
}
