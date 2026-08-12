package com.jarvis.assistant.agent.memory

import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Слой 1: Working Memory (Оперативная память текущего диалога)
 * Хранит контекст текущего момента: активная сущность ("Эмманюэль Макрон"), приложение, контакт, действие.
 */
@Singleton
class WorkingMemory @Inject constructor(
    private val anaphoraEngine: AnaphoraContextEngine
) {
    private val contextStore = mutableMapOf<String, Any>()
    private var lastMentionedApp: String? = null
    private var lastMentionedPerson: String? = null
    private var lastMentionedEntity: String? = null
    private var lastActionExecuted: String? = null

    fun setLastEntity(entity: String) {
        val clean = entity.trim()
        if (clean.isNotBlank()) {
            lastMentionedEntity = clean
            contextStore["last_entity"] = clean
        }
    }

    fun getLastEntity(): String? = lastMentionedEntity

    fun setLastApp(app: String) {
        lastMentionedApp = app
        contextStore["last_app"] = app
    }

    fun getLastApp(): String? = lastMentionedApp

    fun setLastPerson(person: String) {
        lastMentionedPerson = person
        contextStore["last_person"] = person
        setLastEntity(person)
    }

    fun getLastPerson(): String? = lastMentionedPerson

    fun setLastAction(action: String) {
        lastActionExecuted = action
        contextStore["last_action"] = action
    }

    fun getLastAction(): String? = lastActionExecuted

    fun put(key: String, value: Any) {
        contextStore[key] = value
    }

    fun get(key: String): Any? = contextStore[key]

    /**
     * Разрешает местоимения в запросе на основе активного контекста
     */
    fun resolveContextualQuery(rawQuery: String): String {
        val entity = lastMentionedEntity ?: lastMentionedPerson
        return anaphoraEngine.resolveQuery(rawQuery, entity)
    }

    /**
     * Обновляет активную сущность из ответа
     */
    fun updateEntityFromResponse(text: String) {
        val extracted = anaphoraEngine.extractEntity(text)
        if (extracted != null) {
            setLastEntity(extracted)
        }
    }

    fun getWorkingContextSummary(): String {
        val parts = mutableListOf<String>()
        lastMentionedEntity?.let { parts.add("Текущий субъект диалога: $it") }
        lastMentionedApp?.let { parts.add("Активное приложение: $it") }
        lastMentionedPerson?.let { parts.add("Упомянутый контакт: $it") }
        lastActionExecuted?.let { parts.add("Последнее действие: $it") }
        return if (parts.isEmpty()) "" else "Текущий контекст: ${parts.joinToString(", ")}"
    }
}
