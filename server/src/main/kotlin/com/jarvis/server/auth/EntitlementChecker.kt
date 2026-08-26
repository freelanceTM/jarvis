package com.jarvis.server.auth

import com.jarvis.server.license.LicenseService

/**
 * AR-06: порт для проверки entitlement (права на выполнение AI-запроса).
 *
 * AI execution path зависит ТОЛЬКО от этого интерфейса, а не от конкретного
 * [LicenseService]/[BillingService]. Это позволяет подключить dev-реализацию
 * (AlwaysGranted) без поднятия billing-инфраструктуры и не раздувает
 * зависимость слоя роутинга от деталей биллинга.
 */
fun interface EntitlementChecker {
    /**
     * @return true если клиент оплатил доступ (либо ADMIN/INTERNAL — их проверка
     *         делается до вызова checker, см. JarvisApiHandler).
     */
    fun isEntitled(client: AuthenticatedClient): Boolean
}

/**
 * Production-реализация: смотрит на [LicenseService.hasActiveEntitlement].
 */
class LicenseEntitlementChecker(
    private val licenseService: LicenseService
) : EntitlementChecker {
    override fun isEntitled(client: AuthenticatedClient): Boolean =
        client.accountId?.let(licenseService::hasActiveEntitlement) == true
}

/**
 * Dev-only реализация: всегда разрешена. Используется ТОЛЬКО при включении
 * `JARVIS_DEV_MODE=true` (по умолчанию false) — см. Main.kt wiring. Никогда
 * не должна быть заинжектчена в production.
 */
class AlwaysGrantedEntitlementChecker : EntitlementChecker {
    override fun isEntitled(client: AuthenticatedClient): Boolean = true
}
