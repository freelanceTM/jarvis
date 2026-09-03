package com.jarvis.server.auth

import com.jarvis.server.license.LicenseService
import java.security.MessageDigest
import java.util.UUID

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

    /** Создавать checkout для собственного account. */
    CREATE_BILLING_CHECKOUT,

    /** Выпускать и отзывать лицензии. */
    MANAGE_LICENSES,

    /** Смотреть метрики и здоровье провайдеров. */
    VIEW_ADMIN
}

/**
 * Аутентифицированный клиент.
 *
 * Это результат AUTHENTICATION («кто это»). Ответ на вопрос «что ему можно»
 * даёт [Authorizer] — это отдельная ответственность (пункт 7 ТЗ).
 */
enum class AuthSource { STATIC, LICENSE_TOKEN }

data class AuthenticatedClient(
    val clientId: String,
    val tier: ClientTier,
    val accountId: UUID? = null,
    val authSource: AuthSource = AuthSource.STATIC
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

    /**
     * Вариант с контекстом устройства для enforcement-путей (AI-исполнение).
     * Дефолт игнорирует устройство (статические/админ-токены не привязываются);
     * [LicenseTokenAuthenticator] переопределяет и требует совпадение.
     */
    fun authenticate(authorizationHeader: String?, deviceIdHeader: String?): AuthResult =
        authenticate(authorizationHeader)
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

class LicenseTokenAuthenticator(
    private val licenseService: LicenseService
) : Authenticator {

    /**
     * Legacy-путь (validate/checkout): БЕЗ проверки устройства — validate
     * сверяет device_id из тела с лицензией сам, checkout оперирует своим
     * аккаунтом. Токен-как-таковой здесь не даёт AI-доступа.
     */
    override fun authenticate(authorizationHeader: String?): AuthResult =
        authenticateInternal(authorizationHeader, enforceDevice = false, deviceIdHeader = null)

    /**
     * Enforcement-вариант (AI-путь, V007): заголовок устройства ОБЯЗАТЕЛЕН и
     * обязан совпасть с привязкой токена. Отсутствие заголовка — deny
     * (fail-closed): модифицированный клиент не может «забыть» устройство,
     * украденный токен бесполезен с другого устройства.
     */
    override fun authenticate(authorizationHeader: String?, deviceIdHeader: String?): AuthResult =
        authenticateInternal(authorizationHeader, enforceDevice = true, deviceIdHeader = deviceIdHeader)

    private fun authenticateInternal(
        authorizationHeader: String?,
        enforceDevice: Boolean,
        deviceIdHeader: String?
    ): AuthResult {
        if (authorizationHeader.isNullOrBlank()) return AuthResult.MissingCredentials
        val prefix = "Bearer "
        if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) {
            return AuthResult.MissingCredentials
        }
        val token = authorizationHeader.substring(prefix.length).trim()
        if (!token.startsWith("jrv_") || token.length !in 32..256) {
            return AuthResult.InvalidCredentials
        }
        val account = when {
            !enforceDevice -> licenseService.authenticateAccessToken(token)
            deviceIdHeader.isNullOrBlank() -> null // enforcement без устройства — отказ
            else -> licenseService.authenticateAccessToken(token, deviceIdHeader.trim())
        } ?: return AuthResult.InvalidCredentials
        return AuthResult.Success(
            AuthenticatedClient(
                clientId = account.accountId.toString(),
                tier = ClientTier.PRO,
                accountId = account.accountId,
                authSource = AuthSource.LICENSE_TOKEN
            )
        )
    }
}

/** Static admin/bootstrap tokens first, then database-backed license tokens. */
class CompositeAuthenticator(private vararg val delegates: Authenticator) : Authenticator {
    override fun authenticate(authorizationHeader: String?): AuthResult {
        if (authorizationHeader.isNullOrBlank()) return AuthResult.MissingCredentials
        var sawInvalid = false
        delegates.forEach { delegate ->
            when (val result = delegate.authenticate(authorizationHeader)) {
                is AuthResult.Success -> return result
                AuthResult.InvalidCredentials -> sawInvalid = true
                AuthResult.MissingCredentials -> Unit
            }
        }
        return if (sawInvalid) AuthResult.InvalidCredentials else AuthResult.MissingCredentials
    }

    override fun authenticate(authorizationHeader: String?, deviceIdHeader: String?): AuthResult {
        if (authorizationHeader.isNullOrBlank()) return AuthResult.MissingCredentials
        var sawInvalid = false
        delegates.forEach { delegate ->
            when (val result = delegate.authenticate(authorizationHeader, deviceIdHeader)) {
                is AuthResult.Success -> return result
                AuthResult.InvalidCredentials -> sawInvalid = true
                AuthResult.MissingCredentials -> Unit
            }
        }
        return if (sawInvalid) AuthResult.InvalidCredentials else AuthResult.MissingCredentials
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
        ClientTier.PRO to setOf(Permission.EXECUTE_AI, Permission.CREATE_BILLING_CHECKOUT),
        ClientTier.INTERNAL to setOf(Permission.EXECUTE_AI, Permission.CREATE_BILLING_CHECKOUT),
        ClientTier.ADMIN to Permission.entries.toSet()
    )

    override fun isAllowed(client: AuthenticatedClient, permission: Permission): Boolean =
        permissionsByTier[client.tier]?.contains(permission) == true
}
