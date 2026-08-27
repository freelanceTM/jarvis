package com.jarvis.assistant.core.result

import com.jarvis.assistant.agent.decision.PrivacyLevel

/**
 * Generic sealed class representing Data & Domain operations with strongly typed errors.
 */
sealed interface Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>
    data class Error(val exception: Throwable, val message: String? = null) : Resource<Nothing>
    data object Loading : Resource<Nothing>

    /**
     * C-02: домен не может сам выполнить запрос без явного согласия пользователя.
     *
     * Используется для privacy-consent gate: когда [com.jarvis.assistant.agent.decision.ExecutionRequest]
     * имеет effectivePrivacyLevel ∈ {PRIVATE, SENSITIVE} и cloudExplicitlyAllowed=false,
     * use case НЕ падает с Error (чтобы не показать красный тост «Ошибка») и НЕ лезет в
     * сеть. Вместо этого он возвращает NeedsConsent, а UI / голосовой оркестратор
     * показывают пользователю карточку/голосовой запрос «отправить в облако?».
     *
     * @param privacyLevel       вычисленный effective privacy level (PRIVATE или SENSITIVE).
     * @param prompt             человекочитаемый текст вопроса («Запрос содержит email…»).
     * @param retryOnConsentArgs opaque-аргументы, которые UI/voice должен передать обратно
     *                           в use case при согласии (userPrompt + source + privacyLevel).
     *                           Использование data class гарантирует типобезопасность.
     */
    data class NeedsConsent(
        val privacyLevel: PrivacyLevel,
        val prompt: String,
        val retryOnConsentArgs: RetryArgs
    ) : Resource<Nothing> {
        /** Аргументы повторного вызова use case после согласия пользователя. */
        data class RetryArgs(
            val userPrompt: String,
            val source: com.jarvis.assistant.agent.decision.RequestSource,
            val privacyLevel: PrivacyLevel
        )
    }
}

inline fun <T> Resource<T>.onSuccess(action: (value: T) -> Unit): Resource<T> {
    if (this is Resource.Success) action(data)
    return this
}

inline fun <T> Resource<T>.onError(action: (exception: Throwable, message: String?) -> Unit): Resource<T> {
    if (this is Resource.Error) action(exception, message)
    return this
}

inline fun <T> Resource<T>.onLoading(action: () -> Unit): Resource<T> {
    if (this is Resource.Loading) action()
    return this
}

/** C-02: хелпер, чтобы consumers не плодили `when (it) { is NeedsConsent -> … }` руками. */
inline fun <T> Resource<T>.onNeedsConsent(action: (consent: Resource.NeedsConsent) -> Unit): Resource<T> {
    if (this is Resource.NeedsConsent) action(this)
    return this
}
