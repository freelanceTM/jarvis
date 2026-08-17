package com.jarvis.assistant.core.confirmation

/**
 * Распознавание голосовых/текстовых ответов на Confirmation Gate.
 *
 * Единый источник списков «да/нет» — используется текстовым чатом и
 * голосовым оркестратором, чтобы поведение было консистентным.
 *
 * «да»/«нет» проверяются как отдельные слова (word boundary): иначе
 * «погода» содержит подстроку «да», а «интернет» — «нет».
 */
object ConfirmationIntent {

    private val YES_WORD = Regex("""(^|[\s,.;:!?\-])да([\s,.;:!?\-]|$)""")
    private val NO_WORD = Regex("""(^|[\s,.;:!?\-])нет([\s,.;:!?\-]|$)""")

    /** Ответ «Да»: подтверждаем отложенное действие. */
    fun isYes(text: String): Boolean {
        val t = text.lowercase().trim()
        return YES_WORD.containsMatchIn(t) ||
            t.contains("подтверждаю") ||
            t.contains("давай") ||
            t.contains("окей") ||
            t.contains("ок") ||
            t.contains("выполняй") ||
            t.contains("разрешаю") ||
            t.contains("звони") ||
            t.contains("набирай") ||
            t.contains("отправляй") ||
            t.contains("согласен") ||
            t.contains("делай") ||
            t.contains("конечно") ||
            t.contains("ага") ||
            t.contains("добро")
    }

    /** Ответ «Нет»: отменяем отложенное действие. */
    fun isNo(text: String): Boolean {
        val t = text.lowercase().trim()
        return NO_WORD.containsMatchIn(t) ||
            t.contains("отмена") ||
            t.contains("стоп") ||
            t.contains("отменить") ||
            t.contains("не надо") ||
            t.contains("не нужно") ||
            t.contains("отбой") ||
            t.contains("не стоит") ||
            t.contains("передумал") ||
            t.contains("хватит")
    }

    /** Ответ распознан как однозначный да/нет (для быстрого скоупа). */
    fun isDefinitive(text: String): Boolean = isYes(text) || isNo(text)
}
