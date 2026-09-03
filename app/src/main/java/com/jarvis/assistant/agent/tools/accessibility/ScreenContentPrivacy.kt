package com.jarvis.assistant.agent.tools.accessibility

/**
 * Граница распространения экрального контента (Accessibility Lockdown).
 *
 * Требуемый пайплайн:
 *
 * ```
 * Accessibility → capture UI → privacy filter → LLM
 * ```
 *
 * и НЕ:
 *
 * ```
 * Accessibility → полный экран → Cloud LLM
 * ```
 *
 * Пакетный фильтр ([AccessibilityPrivacyPolicy]) и пропуск парольных полей
 * стоят на этапе capture. Этот объект закрывает ВТОРУЮ половину требования:
 * выживший после фильтра текст экрана не должен утекать в облако через
 * историю чата. Точки утечки были:
 *  1. [com.jarvis.assistant.domain.usecases.SendPromptUseCase] —
 *     `saveAssistantMessage(exec.text)` писал summary экрана в
 *     MessageRepository, откуда `getRecentMessages(10)` →
 *     `ExecutionRequest.relatedContent`/`history` → облачный payload;
 *  2. [com.jarvis.assistant.presentation.chat.ChatViewModel]
 *     `confirmPendingAction` — то же самое для bypass-пути.
 *
 * Гарантия: экральный контент можно ПОКАЗАТЬ и ПРОГОВОРИТЬ (локальные
 * поверхности), но нельзя СОХРАНИТЬ в историю — он помечается флагом
 * `containsScreenContent` по всей цепочке ExecutionResult →
 * PromptExecutionResult, а в точках персистентности заменяется на
 * [PLACEHOLDER]. В облачный запрос попадает только placeholder.
 */
object ScreenContentPrivacy {

    /** Единственный инструмент, читающий содержимое экрана. */
    const val SCREEN_READER_TOOL_ID = "accessibility.screen_reader"

    /**
     * Текст, который сохраняется в историю вместо экрального контента.
     * Инертный для последующих запросов: не содержит данных экрана,
     * честно объясняет, почему в истории «пусто».
     */
    const val PLACEHOLDER =
        "[Содержимое экрана прочитано и показано, но не сохраняется — данные экрана остаются на устройстве, сэр.]"

    /** Это вызов чтения экрана? */
    fun isScreenReaderCall(toolId: String): Boolean = toolId == SCREEN_READER_TOOL_ID
}
