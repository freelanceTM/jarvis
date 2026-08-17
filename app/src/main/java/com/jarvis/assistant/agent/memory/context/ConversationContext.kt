package com.jarvis.assistant.agent.memory.context

/**
 * Тип упомянутой сущности. Нужен, чтобы «напиши ему» и «какая там погода»
 * разрешались в разные слоты, а не в одну общую строку lastEntity.
 */
enum class ContextSlot {
    APP,
    CONTACT,
    PERSON,
    FILE,
    LOCATION,
    TOPIC,
    CONVERSATION
}

/**
 * Ссылка, которую пытается разрешить пользователь местоимением.
 */
enum class ReferenceKind {
    /** «ему», «ей», «позвони ему» — нужен адресат-человек. */
    ANIMATE_RECIPIENT,

    /** «он», «она», «про него», «сколько ему лет» — субъект обсуждения. */
    SUBJECT,

    /** «там», «туда» — место. */
    PLACE,

    /** «это», «этот» — последняя тема/объект. */
    OBJECT
}

/**
 * Результат разрешения ссылки.
 */
sealed interface ReferenceResolution {
    /** Ссылку удалось однозначно связать со слотом контекста. */
    data class Resolved(val slot: ContextSlot, val value: String, val rewrittenQuery: String) : ReferenceResolution

    /**
     * В запросе есть местоимение, но подходящего слота в контексте нет.
     * Агент обязан задать уточняющий вопрос вместо угадывания.
     */
    data class NeedsClarification(val kind: ReferenceKind, val question: String) : ReferenceResolution

    /** Местоимений нет — запрос самодостаточен. */
    data object NoReference : ReferenceResolution
}

/**
 * Структурированный контекст диалога.
 *
 * Заменяет подход «одна строка lastEntity + набор regex-замен» на явные слоты.
 * Regex здесь используется только для ДЕТЕКЦИИ ссылки, а не для попытки решить
 * кореференцию русского языка подстановкой строк.
 *
 * Слоты соответствуют модели Entity: person → [lastPerson]/[lastContact],
 * application → [lastApp], location → [lastLocation], object → [lastFile]/[lastTopic],
 * conversation → [lastConversation]. [lastMessage] — последняя реплика пользователя.
 */
data class ConversationContext(
    val lastApp: String? = null,
    val lastContact: String? = null,
    val lastPerson: String? = null,
    val lastFile: String? = null,
    val lastLocation: String? = null,
    val lastAction: String? = null,
    val activeTask: String? = null,
    val lastTopic: String? = null,
    val lastConversation: String? = null,
    val lastMessage: String? = null
) {
    fun valueFor(slot: ContextSlot): String? = when (slot) {
        ContextSlot.APP -> lastApp
        ContextSlot.CONTACT -> lastContact
        ContextSlot.PERSON -> lastPerson
        ContextSlot.FILE -> lastFile
        ContextSlot.LOCATION -> lastLocation
        ContextSlot.TOPIC -> lastTopic
        ContextSlot.CONVERSATION -> lastConversation
    }

    fun with(slot: ContextSlot, value: String): ConversationContext {
        val clean = value.trim()
        if (clean.isEmpty()) return this
        return when (slot) {
            ContextSlot.APP -> copy(lastApp = clean)
            ContextSlot.CONTACT -> copy(lastContact = clean, lastPerson = lastPerson ?: clean)
            ContextSlot.PERSON -> copy(lastPerson = clean)
            ContextSlot.FILE -> copy(lastFile = clean)
            ContextSlot.LOCATION -> copy(lastLocation = clean)
            ContextSlot.TOPIC -> copy(lastTopic = clean)
            ContextSlot.CONVERSATION -> copy(lastConversation = clean)
        }
    }

    fun isEmpty(): Boolean = listOfNotNull(
        lastApp, lastContact, lastPerson, lastFile, lastLocation, lastTopic, lastConversation
    ).isEmpty()

    fun summary(): String {
        val parts = buildList {
            lastApp?.let { add("активное приложение: $it") }
            lastContact?.let { add("последний контакт: $it") }
            lastPerson?.let { add("обсуждаемый человек: $it") }
            lastLocation?.let { add("место: $it") }
            lastFile?.let { add("файл: $it") }
            lastTopic?.let { add("тема: $it") }
            lastConversation?.let { add("диалог: $it") }
            activeTask?.let { add("текущая задача: $it") }
            lastAction?.let { add("последнее действие: $it") }
        }
        return if (parts.isEmpty()) "" else "Текущий контекст: ${parts.joinToString(", ")}"
    }
}
