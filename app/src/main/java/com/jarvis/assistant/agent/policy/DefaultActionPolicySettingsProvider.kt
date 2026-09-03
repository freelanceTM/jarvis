package com.jarvis.assistant.agent.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Дефолтный провайдер настроек политики: in-memory StateFlow с безопасными
 * значениями по умолчанию (звонки и сообщения — ВСЕГДА с подтверждением,
 * доверенных контактов нет).
 *
 * Настройка из UI — следующий шаг: экран «Политика подтверждений» пишет
 * через [update]; формат хранения (DataStore) не влияет на контракт, поэтому
 * движок и тесты не зависят от способа персистенции. До появления UI
 * значения остаются дефолтными — это честная позиция безопасности
 * (строже — лучше).
 */
@Singleton
class DefaultActionPolicySettingsProvider @Inject constructor() : ActionPolicySettingsProvider {

    private val state = MutableStateFlow(ActionPolicySettings())

    override val settings: StateFlow<ActionPolicySettings> = state.asStateFlow()

    override fun current(): ActionPolicySettings = state.value

    override suspend fun update(transform: (ActionPolicySettings) -> ActionPolicySettings) {
        state.value = transform(state.value)
    }
}
