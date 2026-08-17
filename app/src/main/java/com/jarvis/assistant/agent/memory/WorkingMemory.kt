package com.jarvis.assistant.agent.memory

import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ContextSlot
import com.jarvis.assistant.agent.memory.context.ConversationContext
import com.jarvis.assistant.agent.memory.context.ReferenceResolution
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Слой 1: Working Memory (оперативная память текущего диалога).
 *
 * v0.2: вместо одной строки lastEntity хранится структурированный
 * [ConversationContext] со слотами (приложение, контакт, человек, место, файл).
 * Разрешение местоимений идёт через [ReferenceResolver]; если подходящего
 * слота нет, диалоговый слой обязан задать уточняющий вопрос.
 */
@Singleton
class WorkingMemory @Inject constructor(
    private val anaphoraEngine: AnaphoraContextEngine,
    private val referenceResolver: ReferenceResolver
) {
    private val contextStore = mutableMapOf<String, Any>()

    @Volatile
    var context: ConversationContext = ConversationContext()
        private set

    // ------------------------------------------------------------- слоты

    fun setLastEntity(entity: String) {
        val clean = entity.trim()
        if (clean.isBlank()) return
        context = context.with(ContextSlot.PERSON, clean).copy(lastTopic = clean)
        contextStore["last_entity"] = clean
    }

    fun getLastEntity(): String? = context.lastPerson ?: context.lastTopic

    fun setLastApp(app: String) {
        context = context.with(ContextSlot.APP, app)
        contextStore["last_app"] = app
    }

    fun getLastApp(): String? = context.lastApp

    fun setLastPerson(person: String) {
        context = context.with(ContextSlot.PERSON, person)
        contextStore["last_person"] = person
    }

    fun getLastPerson(): String? = context.lastPerson

    fun setLastContact(contact: String) {
        context = context.with(ContextSlot.CONTACT, contact)
        contextStore["last_contact"] = contact
    }

    fun getLastContact(): String? = context.lastContact

    fun setLastLocation(location: String) {
        context = context.with(ContextSlot.LOCATION, location)
        contextStore["last_location"] = location
    }

    fun getLastLocation(): String? = context.lastLocation

    fun setLastAction(action: String) {
        context = context.copy(lastAction = action)
        contextStore["last_action"] = action
    }

    fun getLastAction(): String? = context.lastAction

    fun setLastMessage(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        context = context.copy(lastMessage = clean)
        contextStore["last_message"] = clean
    }

    fun getLastMessage(): String? = context.lastMessage

    fun setLastConversation(conversation: String) {
        val clean = conversation.trim()
        if (clean.isBlank()) return
        context = context.with(ContextSlot.CONVERSATION, clean)
        contextStore["last_conversation"] = clean
    }

    fun getLastConversation(): String? = context.lastConversation

    fun setActiveTask(task: String?) {
        context = context.copy(activeTask = task)
    }

    fun put(key: String, value: Any) {
        contextStore[key] = value
    }

    fun get(key: String): Any? = contextStore[key]

    fun clearContext() {
        context = ConversationContext()
        contextStore.clear()
    }

    // ------------------------------------------------------- разрешение ссылок

    /**
     * Разрешает отсылки в запросе на основе структурированного контекста.
     * Возвращает [ReferenceResolution], чтобы вызывающий слой мог отличить
     * «переписал запрос» от «нужно уточнение у пользователя».
     */
    fun resolveReference(rawQuery: String): ReferenceResolution =
        referenceResolver.resolve(rawQuery, context)

    /**
     * Совместимый со старым кодом вариант: возвращает переписанный запрос,
     * а если разрешить ссылку нельзя — исходный запрос без изменений
     * (подстановка «наугад» недопустима).
     */
    fun resolveContextualQuery(rawQuery: String): String =
        when (val resolution = resolveReference(rawQuery)) {
            is ReferenceResolution.Resolved -> resolution.rewrittenQuery
            is ReferenceResolution.NeedsClarification -> rawQuery
            ReferenceResolution.NoReference -> rawQuery
        }

    /**
     * Обновляет тему диалога по тексту ответа ассистента.
     */
    fun updateEntityFromResponse(text: String) {
        anaphoraEngine.extractEntity(text)?.let { setLastEntity(it) }
    }

    fun getWorkingContextSummary(): String = context.summary()
}
