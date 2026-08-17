package com.jarvis.assistant.agent.memory.context

/**
 * Типизированная сущность диалогового контекста (Entity).
 *
 * Модель из требований v0.2:
 *
 *   Entity
 *    ├── person        — человек (контакт, собеседник, обсуждаемый)
 *    ├── application   — приложение (Telegram, YouTube, ...)
 *    ├── location      — место (Берлин, дом, офис, ...)
 *    ├── object        — предмет/файл/тема (документ, «это», ...)
 *    └── conversation  — диалог/канал (чат в Telegram, SMS-переписка, ...)
 *
 * Сущность хранит «именительную» форму ([displayName]) — именно она
 * подставляется при разрешении местоимений и передаётся инструментам.
 */
sealed interface Entity {
    /** Стабильный тип сущности. */
    val type: EntityType

    /** Имя сущности в именительном падеже («мама», «Telegram», «Берлин»). */
    val displayName: String

    /** Краткое описание для UI/логов. */
    val description: String
}

/** Типы сущностей (соответствуют слотам контекста). */
enum class EntityType {
    PERSON,
    APPLICATION,
    LOCATION,
    OBJECT,
    CONVERSATION
}

data class PersonEntity(
    override val displayName: String,
    val isContact: Boolean = false
) : Entity {
    override val type: EntityType = EntityType.PERSON
    override val description: String = if (isContact) "контакт: $displayName" else "человек: $displayName"
}

data class ApplicationEntity(
    override val displayName: String
) : Entity {
    override val type: EntityType = EntityType.APPLICATION
    override val description: String = "приложение: $displayName"
}

data class LocationEntity(
    override val displayName: String
) : Entity {
    override val type: EntityType = EntityType.LOCATION
    override val description: String = "место: $displayName"
}

data class ObjectEntity(
    override val displayName: String
) : Entity {
    override val type: EntityType = EntityType.OBJECT
    override val description: String = "объект: $displayName"
}

data class ConversationEntity(
    override val displayName: String,
    val channel: String? = null
) : Entity {
    override val type: EntityType = EntityType.CONVERSATION
    override val description: String = "диалог${channel?.let { " в $it" } ?: ""}: $displayName"
}

/**
 * Маппинг слотов контекста в типизированные сущности.
 */
fun ContextSlot.toEntity(displayName: String): Entity? = when (this) {
    ContextSlot.PERSON -> PersonEntity(displayName)
    ContextSlot.CONTACT -> PersonEntity(displayName, isContact = true)
    ContextSlot.APP -> ApplicationEntity(displayName)
    ContextSlot.LOCATION -> LocationEntity(displayName)
    ContextSlot.FILE -> ObjectEntity(displayName)
    ContextSlot.TOPIC -> ObjectEntity(displayName)
    ContextSlot.CONVERSATION -> ConversationEntity(displayName)
}
