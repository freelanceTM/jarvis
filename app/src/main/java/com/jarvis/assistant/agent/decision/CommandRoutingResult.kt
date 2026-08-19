package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.agent.fast.FastRouteResult
import com.jarvis.assistant.agent.model.ToolCall

/**
 * Результат быстрого роутинга, дополненный уверенностью.
 *
 * ВАЖНО: [com.jarvis.assistant.agent.fast.FastCommandRouter] возвращает
 * [FastRouteResult] БЕЗ числовой уверенности — это детерминированный
 * rule-based NLU («правило совпало» / «не совпало»). Второй механизм
 * confidence в роутере не создаётся: здесь выполняется минимальная адаптация
 * существующего результата к контракту decision engine.
 *
 * Сам FastCommandRouter не изменён — обратная совместимость сохранена.
 */
sealed class CommandRoutingResult {

    abstract val confidence: Float

    /** Роутер уверенно распознал команду устройства и собрал вызов инструмента. */
    data class DeviceCommand(
        val toolCall: ToolCall,
        val immediateVoiceResponse: String,
        override val confidence: Float
    ) : CommandRoutingResult()

    /**
     * Роутер уверенно распознал реплику, на которую есть готовый локальный
     * ответ без вызова инструмента («привет», «ты тут?»).
     */
    data class DirectResponse(
        val text: String,
        override val confidence: Float
    ) : CommandRoutingResult()

    /** Правило не совпало — запрос уходит дальше по цепочке. */
    data class Unknown(
        override val confidence: Float = 0f
    ) : CommandRoutingResult()
}

/**
 * Адаптер FastRouteResult → CommandRoutingResult.
 *
 * Значения уверенности детерминированы и отражают природу существующего
 * роутера (правило совпало = высокая уверенность). Порог сравнения — в
 * [ExecutionDecisionConfig.deviceConfidenceThreshold].
 */
object FastRouteConfidence {

    /** Совпало правило с конкретным инструментом — максимальная уверенность. */
    const val TOOL_MATCH = 0.95f

    /** Совпала реплика без инструмента (приветствие, «ты тут?»). */
    const val DIRECT_RESPONSE = 0.80f

    /** Правило не совпало. */
    const val NO_MATCH = 0.0f

    fun from(result: FastRouteResult): CommandRoutingResult = when (result) {
        is FastRouteResult.HandledLocally -> {
            val call = result.toolCall
            if (call != null) {
                CommandRoutingResult.DeviceCommand(
                    toolCall = call,
                    immediateVoiceResponse = result.immediateVoiceResponse,
                    confidence = TOOL_MATCH
                )
            } else {
                CommandRoutingResult.DirectResponse(
                    text = result.immediateVoiceResponse,
                    confidence = DIRECT_RESPONSE
                )
            }
        }

        FastRouteResult.ForwardToLlm -> CommandRoutingResult.Unknown(NO_MATCH)
    }
}
