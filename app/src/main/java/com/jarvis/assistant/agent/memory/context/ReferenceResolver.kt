package com.jarvis.assistant.agent.memory.context

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разрешение отсылок в диалоге поверх [ConversationContext].
 *
 * Осознанное ограничение v0.2: мы не пытаемся решить кореференцию русского
 * языка набором regex-замен. Regex используется только чтобы **обнаружить**
 * тип ссылки; дальше решение принимается по структурированным слотам, а при
 * отсутствии подходящего слота агент задаёт уточняющий вопрос.
 */
@Singleton
class ReferenceResolver @Inject constructor() {

    fun detectReference(query: String): ReferenceKind? {
        val q = " ${query.lowercase().replace('ё', 'е').trim()} "

        // Адресат действия: "напиши ему", "позвони ей", "отправь им"
        if (RECIPIENT_VERBS.any { q.contains(" $it ") } && RECIPIENT_PRONOUNS.any { q.contains(" $it ") }) {
            return ReferenceKind.ANIMATE_RECIPIENT
        }
        if (PLACE_PRONOUNS.any { q.contains(" $it ") }) return ReferenceKind.PLACE
        if (SUBJECT_PRONOUNS.any { q.contains(" $it ") }) return ReferenceKind.SUBJECT
        if (OBJECT_PRONOUNS.any { q.contains(" $it ") }) return ReferenceKind.OBJECT
        return null
    }

    /**
     * Пытается разрешить ссылку. Если нужного слота нет — возвращает
     * [ReferenceResolution.NeedsClarification], а не подставляет случайное значение.
     */
    fun resolve(query: String, context: ConversationContext): ReferenceResolution {
        val kind = detectReference(query) ?: return ReferenceResolution.NoReference

        return when (kind) {
            ReferenceKind.ANIMATE_RECIPIENT -> {
                val recipient = context.lastContact ?: context.lastPerson
                if (recipient != null) {
                    ReferenceResolution.Resolved(
                        slot = if (context.lastContact != null) ContextSlot.CONTACT else ContextSlot.PERSON,
                        value = recipient,
                        rewrittenQuery = substitute(query, RECIPIENT_PRONOUNS, recipient)
                    )
                } else {
                    ReferenceResolution.NeedsClarification(kind, "Кому именно, сэр?")
                }
            }

            ReferenceKind.SUBJECT -> {
                val subject = context.lastPerson ?: context.lastTopic ?: context.lastContact
                if (subject != null) {
                    ReferenceResolution.Resolved(
                        slot = ContextSlot.PERSON,
                        value = subject,
                        rewrittenQuery = substitute(query, SUBJECT_PRONOUNS + RECIPIENT_PRONOUNS, subject)
                    )
                } else {
                    ReferenceResolution.NeedsClarification(kind, "О ком именно речь, сэр?")
                }
            }

            ReferenceKind.PLACE -> {
                val place = context.lastLocation
                if (place != null) {
                    ReferenceResolution.Resolved(
                        slot = ContextSlot.LOCATION,
                        value = place,
                        rewrittenQuery = substitute(query, PLACE_PRONOUNS, "в $place")
                    )
                } else {
                    ReferenceResolution.NeedsClarification(kind, "Какое место вы имеете в виду, сэр?")
                }
            }

            ReferenceKind.OBJECT -> {
                val obj = context.lastFile ?: context.lastTopic ?: context.lastApp
                if (obj != null) {
                    ReferenceResolution.Resolved(
                        slot = if (context.lastFile != null) ContextSlot.FILE else ContextSlot.TOPIC,
                        value = obj,
                        rewrittenQuery = substitute(query, OBJECT_PRONOUNS, obj)
                    )
                } else {
                    ReferenceResolution.NeedsClarification(kind, "Что именно вы имеете в виду, сэр?")
                }
            }
        }
    }

    private fun substitute(query: String, pronouns: List<String>, replacement: String): String {
        var result = query
        for (pronoun in pronouns.sortedByDescending { it.length }) {
            result = result.replace(
                Regex("(?iu)(?<![\\p{L}])$pronoun(?![\\p{L}])"),
                replacement
            )
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        val RECIPIENT_VERBS = listOf(
            "напиши", "напишите", "позвони", "позвоните", "набери", "отправь",
            "отправьте", "перезвони", "скинь", "сообщи"
        )
        val RECIPIENT_PRONOUNS = listOf("ему", "ей", "им", "ним", "нему", "ней")
        // Дательный падеж ("сколько ЕМУ лет") — тоже отсылка к субъекту,
        // если рядом нет глагола-адресата ("напиши ему").
        val SUBJECT_PRONOUNS = listOf(
            "он", "она", "они", "оно", "его", "ее", "их",
            "нем", "нее", "них", "ему", "ей", "им"
        )
        val PLACE_PRONOUNS = listOf("там", "туда", "оттуда")
        val OBJECT_PRONOUNS = listOf("это", "этот", "эту", "эти", "этого", "той", "тот")
    }
}
