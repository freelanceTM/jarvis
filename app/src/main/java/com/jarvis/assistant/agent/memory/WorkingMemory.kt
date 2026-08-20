package com.jarvis.assistant.agent.memory

import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ContextSlot
import com.jarvis.assistant.agent.memory.context.ConversationContext
import com.jarvis.assistant.agent.memory.context.ReferenceResolution
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Слой 1: Working Memory (оперативная память текущего диалога).
 *
 * v0.2: вместо одной строки lastEntity хранится структурированный
 * [ConversationContext] со слотами (приложение, контакт, человек, место, файл).
 * Разрешение местоимений идёт через [ReferenceResolver]; если подходящего
 * слота нет, диалоговый слой обязан задать уточняющий вопрос.
 *
 * [contextStore] — вспомогательный кэш observation-данных (battery_percent,
 * current_time, wifi_enabled, ...). Пункт аудита #10: ограничен LRU-емкостью
 * ([MAX_CONTEXT_ENTRIES]) и TTL ([TTL_MS]) — в долгих сессиях не растёт
 * бесконечно.
 */
@Singleton
class WorkingMemory @Inject constructor(
    private val anaphoraEngine: AnaphoraContextEngine,
    private val referenceResolver: ReferenceResolver
) {
    companion object {
        /** Предел записей contextStore: старейшие по использованию вытесняются (LRU). */
        const val MAX_CONTEXT_ENTRIES = 128

        /** Время жизни записи contextStore: 30 минут без обращения. */
        const val TTL_MS = 30L * 60 * 1000L
    }

    /** Запись кэша с моментом последнего обращения (для TTL). */
    private data class TimedEntry(val value: Any, var lastAccess: Long)

    /**
     * LRU-кэш: LinkedHashMap с accessOrder=true — при переполнении
     * вытесняется запись, к которой дольше всех не обращались.
     * Все обращения к mutable map и compound-обновления [context]
     * сериализованы монитором экземпляра: observation работает на Dispatchers.IO,
     * одновременно с голосовым и UI-конвейерами.
     */
    private val contextStore = object : LinkedHashMap<String, TimedEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedEntry>?): Boolean =
            size > MAX_CONTEXT_ENTRIES
    }

    @Volatile
    var context: ConversationContext = ConversationContext()
        private set

    // ------------------------------------------------------------- слоты

    @Synchronized
    fun setLastEntity(entity: String) {
        val clean = entity.trim()
        if (clean.isBlank()) return
        context = context.with(ContextSlot.PERSON, clean).copy(lastTopic = clean)
        contextStore["last_entity"] = TimedEntry(clean, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastEntity(): String? = context.lastPerson ?: context.lastTopic

    @Synchronized
    fun setLastApp(app: String) {
        context = context.with(ContextSlot.APP, app)
        contextStore["last_app"] = TimedEntry(app, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastApp(): String? = context.lastApp

    @Synchronized
    fun setLastPerson(person: String) {
        context = context.with(ContextSlot.PERSON, person)
        contextStore["last_person"] = TimedEntry(person, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastPerson(): String? = context.lastPerson

    @Synchronized
    fun setLastContact(contact: String) {
        context = context.with(ContextSlot.CONTACT, contact)
        contextStore["last_contact"] = TimedEntry(contact, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastContact(): String? = context.lastContact

    @Synchronized
    fun setLastLocation(location: String) {
        context = context.with(ContextSlot.LOCATION, location)
        contextStore["last_location"] = TimedEntry(location, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastLocation(): String? = context.lastLocation

    @Synchronized
    fun setLastAction(action: String) {
        context = context.copy(lastAction = action)
        contextStore["last_action"] = TimedEntry(action, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastAction(): String? = context.lastAction

    @Synchronized
    fun setLastMessage(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        context = context.copy(lastMessage = clean)
        contextStore["last_message"] = TimedEntry(clean, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastMessage(): String? = context.lastMessage

    @Synchronized
    fun setLastConversation(conversation: String) {
        val clean = conversation.trim()
        if (clean.isBlank()) return
        context = context.with(ContextSlot.CONVERSATION, clean)
        contextStore["last_conversation"] = TimedEntry(clean, System.currentTimeMillis())
    }

    @Synchronized
    fun getLastConversation(): String? = context.lastConversation

    @Synchronized
    fun setActiveTask(task: String?) {
        context = context.copy(activeTask = task)
    }

    // ------------------------------------------------------- contextStore (LRU + TTL)

    /**
     * Пункт аудита #10: запись в ограниченный кэш с TTL.
     * Перед записью вытесняются истёкшие записи.
     */
    @Synchronized
    fun put(key: String, value: Any) {
        evictExpired()
        contextStore[key] = TimedEntry(value, System.currentTimeMillis())
    }

    /**
     * Пункт аудита #10: чтение с обновлением времени обращения (LRU).
     * Истёкшие записи при чтении удаляются и возвращают null.
     */
    @Synchronized
    fun get(key: String): Any? {
        val now = System.currentTimeMillis()
        val entry = contextStore[key] ?: return null
        if (now - entry.lastAccess > TTL_MS) {
            contextStore.remove(key)
            return null
        }
        entry.lastAccess = now
        return entry.value
    }

    /**
     * Удаляет записи, к которым не обращались дольше [TTL_MS].
     * Вызывается при каждом put; публичен для диагностики и тестов.
     */
    @Synchronized
    fun evictExpired(now: Long = System.currentTimeMillis()) {
        val expired = contextStore.entries
            .filter { now - it.value.lastAccess > TTL_MS }
            .map { it.key }
        expired.forEach { contextStore.remove(it) }
    }

    /** Текущее число записей contextStore (диагностика). */
    @Synchronized
    fun contextStoreSize(): Int = contextStore.size

    @Synchronized
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
    @Synchronized
    fun resolveReference(rawQuery: String): ReferenceResolution =
        referenceResolver.resolve(rawQuery, context)

    /**
     * Совместимый со старым кодом вариант: возвращает переписанный запрос,
     * а если разрешить ссылку нельзя — исходный запрос без изменений
     * (подстановка «наугад» недопустима).
     */
    @Synchronized
    fun resolveContextualQuery(rawQuery: String): String =
        when (val resolution = resolveReference(rawQuery)) {
            is ReferenceResolution.Resolved -> resolution.rewrittenQuery
            is ReferenceResolution.NeedsClarification -> rawQuery
            ReferenceResolution.NoReference -> rawQuery
        }

    /**
     * Обновляет тему диалога по тексту ответа ассистента.
     */
    @Synchronized
    fun updateEntityFromResponse(text: String) {
        anaphoraEngine.extractEntity(text)?.let { setLastEntity(it) }
    }

    @Synchronized
    fun getWorkingContextSummary(): String = context.summary()
}
