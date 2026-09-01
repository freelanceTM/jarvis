package com.jarvis.server.admin

/**
 * OMNIX Control Plane RBAC (Control Plane ТЗ §5).
 *
 * Роли и гранулярные permissions. Проверка выполняется ТОЛЬКО на backend,
 * на каждом privileged-запросе (frontend-authorization запрещён ТЗ).
 */
enum class AdminRole {
    SUPER_ADMIN,
    ADMIN,
    SUPPORT,
    VIEWER
}

enum class AdminPermission {
    DASHBOARD_READ,
    USERS_READ,
    DEVICES_READ,
    DEVICES_REVOKE,
    LICENSES_READ,
    LICENSES_WRITE,
    SUBSCRIPTIONS_READ,
    PROVIDERS_READ,
    PROVIDERS_CONFIGURE,
    USAGE_READ,
    LOGS_READ,
    AUDIT_READ,
    SETTINGS_READ,
    SETTINGS_WRITE,
    FEATURES_READ,
    FEATURES_WRITE,
    /** Управление самими admin-аккаунтами. Только [AdminRole.SUPER_ADMIN]. */
    ADMINS_MANAGE
}

/** Аутентифицированный оператор Control Plane. */
data class AdminPrincipal(
    val accountId: java.util.UUID?,
    /** `admin:<username>` для БД-аккаунтов, `token:<clientId>` для legacy static-токенов. */
    val actor: String,
    val role: AdminRole,
    /** id активной сессии; null для legacy static-токенов. */
    val sessionId: java.util.UUID? = null
)

/**
 * Единая матрица роль → permissions. Источник истины для unit-теста матрицы
 * и для runtime-проверок. Наследования нет: матрица явная, чтобы ревьюить
 * права глазами, а не выводить их цепочкой.
 */
object AdminRbac {

    private val matrix: Map<AdminRole, Set<AdminPermission>> = mapOf(
        AdminRole.SUPER_ADMIN to AdminPermission.entries.toSet(),
        AdminRole.ADMIN to AdminPermission.entries.toSet() - AdminPermission.ADMINS_MANAGE,
        AdminRole.SUPPORT to setOf(
            AdminPermission.DASHBOARD_READ,
            AdminPermission.USERS_READ,
            AdminPermission.DEVICES_READ,
            AdminPermission.LICENSES_READ,
            AdminPermission.LICENSES_WRITE,
            AdminPermission.SUBSCRIPTIONS_READ,
            AdminPermission.USAGE_READ,
            AdminPermission.LOGS_READ,
            AdminPermission.AUDIT_READ,
            AdminPermission.FEATURES_READ,
            AdminPermission.SETTINGS_READ,
            AdminPermission.PROVIDERS_READ
        ),
        AdminRole.VIEWER to AdminPermission.entries
            .filter { it.name.endsWith("_READ") }
            .toSet()
    )

    fun can(role: AdminRole, permission: AdminPermission): Boolean =
        matrix[role]?.contains(permission) == true

    fun permissionsOf(role: AdminRole): Set<AdminPermission> =
        matrix[role].orEmpty()
}
