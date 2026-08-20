package com.jarvis.server.auth

import java.security.MessageDigest

/**
 * Роли клиента (пункт 7 ТЗ).
 *
 * Полноценный billing сейчас не нужен, но модель заложена, чтобы добавить
 * тарифы без переписывания API.
 */
enum class ClientTier { FREE, PRO, ADMIN, INTERNAL }

/** Разрешения — отвечают на вопрос «что клиенту МОЖНО». */
enum class Permission {
    /** Выполнять облачные AI-запросы. */
    EXECUTE_AI,

    /** Смотреть метрики и здоровье провайдеров. */
    VIEW_ADMIN
}

/**
 * Аутентифицированный клиент.
 *
 * Это результат AUTHENTICATION («кто это»). Ответ на вопрос «что ему можно»
 * даёт [Authorizer] — это отдельная ответственность (пункт 7 ТЗ).
 */
data class AuthenticatedClient(
    val clientId: String,
    val tier: ClientTier
)

/** Ошибки аутентификации. Различаем «нет токена» и «токен неизвестен». */
sealed class AuthResult {
    data class Success(val client: AuthenticatedClient) : AuthResult()
    data object MissingCredentials : AuthResult()
    data object InvalidCredentials : AuthResult()
}

/**
 * AUTHENTICATION: кто этот клиент.
 *
 * Механизм — bearer-токен в заголовке `Authorization`. Выбран потому, что
 * в проекте уже есть `AuthInterceptor` с bearer-схемой и защищённое хранилище
 * (`EncryptedSharedPreferences`) на Android — то есть это переиспользование
 * существующего подхода, а не новая сущность.
 *
 * Токены НЕ хранятся в коде: приходят из конфигурации (env), а сравниваются
 * в виде SHA-256 и константным по времени сравнением.
 */
interface Authenticator {
    fun authenticate(authorizationHeader: String?): AuthResult
}

class TokenAuthenticator(
    /** token → clientId. */
    tokens: Map<String, String>,
    private val tierResolver: (clientId: String) -> ClientTier = { ClientTier.FREE }
) : Authenticator {

    /** Храним хеши, а не сами токены. */
    private val hashedTokens: Map<String, String> =
        tokens.entries.associate { (token, clientId) -> sha256(token) to clientId }

    override fun authenticate(authorizationHeader: String?): AuthResult {
        if (authorizationHeader.isNullOrBlank()) return AuthResult.MissingCredentials

        val prefix = "Bearer "
        if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) {
            return AuthResult.MissingCredentials
        }

        val token = authorizationHeader.substring(prefix.length).trim()
        if (token.isEmpty()) return AuthResult.MissingCredentials

        val hash = sha256(token)
        val clientId = hashedTokens.entries
            .firstOrNull { constantTimeEquals(it.key, hash) }
            ?.value
            ?: return AuthResult.InvalidCredentials

        return AuthResult.Success(
            AuthenticatedClient(clientId = clientId, tier = tierResolver(clientId))
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Защита от timing-атак при сравнении секретов. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/**
 * AUTHORIZATION: что этому клиенту разрешено.
 *
 * Отделено от аутентификации намеренно (пункт 7 ТЗ): добавление тарифов
 * FREE/PRO сведётся к правке этой таблицы, без изменения API и роутера.
 */
interface Authorizer {
    fun isAllowed(client: AuthenticatedClient, permission: Permission): Boolean
}

class TierAuthorizer : Authorizer {

    private val permissionsByTier: Map<ClientTier, Set<Permission>> = mapOf(
        ClientTier.FREE to setOf(Permission.EXECUTE_AI),
        ClientTier.PRO to setOf(Permission.EXECUTE_AI),
        ClientTier.INTERNAL to setOf(Permission.EXECUTE_AI),
        ClientTier.ADMIN to setOf(Permission.EXECUTE_AI, Permission.VIEW_ADMIN)
    )

    override fun isAllowed(client: AuthenticatedClient, permission: Permission): Boolean =
        permissionsByTier[client.tier]?.contains(permission) == true
}
