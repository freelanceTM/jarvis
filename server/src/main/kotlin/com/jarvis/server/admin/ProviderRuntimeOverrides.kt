package com.jarvis.server.admin

import com.jarvis.server.provider.ProviderId
import java.util.concurrent.atomic.AtomicReference

/**
 * RUNTIME-конфигурация провайдеров (Control Plane ТЗ §12).
 *
 * Административные overrides поверх startup-конфига. Изменение проходит
 * полный цикл `Validate → Persist (admin_settings) → Audit → Apply`
 * (apply = атомарная замена этой reference, читается на каждый select).
 *
 * Намеренно поддержаны только priority/enabled: они безопасны для клиентов
 * в любой момент. Timeout/retry менять в рантайме нельзя — они входят в
 * client-facing deadline-бюджет (CR-06), поэтому в AdminSettings.AiRoutingSettings
 * их нет, а API помечает их `requiresRestart: true`.
 */
class ProviderRuntimeOverrides {

    data class Override(
        val enabled: Boolean,
        /** null = использовать startup-priority. */
        val priority: Int?
    )

    private val current = AtomicReference<Map<ProviderId, Override>>(emptyMap())

    fun snapshot(): Map<ProviderId, Override> = current.get()

    /** Атомарно применяет новый набор (уже валидированный settings-сервисом). */
    fun apply(overrides: Map<ProviderId, Override>) {
        current.set(overrides.toMap())
    }

    fun enabled(id: ProviderId): Boolean? = current.get()[id]?.enabled

    fun priority(id: ProviderId): Int? = current.get()[id]?.priority
}
