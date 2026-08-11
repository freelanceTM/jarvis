package com.jarvis.assistant.agent.memory

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Слой 1: Working Memory (Оперативная память текущего диалога)
 * Хранит контекст текущего момента: активное приложение, упомянутые сущности ("там", "ему").
 */
@Singleton
class WorkingMemory @Inject constructor() {
    private val contextStore = mutableMapOf<String, Any>()
    private var lastMentionedApp: String? = null
    private var lastMentionedPerson: String? = null
    private var lastActionExecuted: String? = null

    fun setLastApp(app: String) {
        lastMentionedApp = app
        contextStore["last_app"] = app
    }

    fun getLastApp(): String? = lastMentionedApp

    fun setLastPerson(person: String) {
        lastMentionedPerson = person
        contextStore["last_person"] = person
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

    fun getWorkingContextSummary(): String {
        val parts = mutableListOf<String>()
        lastMentionedApp?.let { parts.add("Активное приложение: $it") }
        lastMentionedPerson?.let { parts.add("Упомянутый контакт: $it") }
        lastActionExecuted?.let { parts.add("Последнее действие: $it") }
        return if (parts.isEmpty()) "" else "Текущий контекст: ${parts.joinToString(", ")}"
    }
}
