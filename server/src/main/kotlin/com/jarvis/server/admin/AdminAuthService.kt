package com.jarvis.server.admin

import com.jarvis.server.auth.AuthResult
import com.jarvis.server.auth.Authenticator
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.ratelimit.PostgresRateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Результат [AdminAuthService.login]. */
sealed class AdminLoginResult {
    data class Success(val rawToken: String, val principal: AdminPrincipal, val expiresAt: Instant) : AdminLoginResult()
    data object InvalidCredentials : AdminLoginResult()
    data object AccountDisabled : AdminLoginResult()
    data object RateLimited : AdminLoginResult()
}

/** Результат проверки bearer/cookie-токена Control Plane. */
sealed class AdminAuthResult {
    data class Success(val principal: AdminPrincipal) : AdminAuthResult()
    data object Unauthenticated : AdminAuthResult()
}

/** Конфигурация admin-безопасности (секция SECURITY settings, ТЗ §20). */
data class AdminSecurityPolicy(
    /** TTL сессии. Короткоживущий access + sliding renewal. */
    val sessionTtl: Duration = Duration.ofMinutes(30),
    /** Максимум попыток логина на пару username+ip (и в минуту, и в сутки). */
    val loginMaxAttempts: Int = 5
) {
    init {
        require(!sessionTtl.isZero && !sessionTtl.isNegative && sessionTtl <= Duration.ofHours(12)) {
            "admin session TTL must be in (0..12h]"
        }
        require(loginMaxAttempts in 1..100) { "loginMaxAttempts must be in 1..100" }
    }
}

/**
 * ADMIN AUTHENTICATION (Control Plane ТЗ §4):
 *
 * ```
 * Admin Account → Authentication → Session → RBAC → Admin API
 * ```
 *
 * - login по username/password (PBKDF2), brute-force через [PostgresRateLimiter]
 *   (переиспользование существующего персистентного лимитера, scope `admin_login`);
 * - короткоживущие session-токены, хранятся только хеши;
 * - logout / revocation / sliding re-issue;
 * - legacy static admin-токены (env) продолжают работать как SUPER_ADMIN —
 *   обратная совместимость с существующими /v1/admin/licenses/* (ТЗ §23:
 *   «сначала проверь существующие routes»).
 *
 * Подготовка к 2FA: точка расширения — [AdminLoginResult.Success] после
 * отдельного second-factor шага; схема БД менять не потребуется
 * (секрет фактора появится в admin_accounts.metadata-колонке при вводе).
 */
class AdminAuthService(
    private val accounts: AdminAccountRepository,
    private val sessions: AdminSessionRepository,
    private val loginRateLimiter: PostgresRateLimiter,
    private val policy: AdminSecurityPolicy = AdminSecurityPolicy(),
    private val clock: Clock = Clock.systemUTC()
) {

    fun login(username: String, password: String, remoteAddress: String?): AdminLoginResult {
        val now = clock.instant()
        val lockKey = "admin-login|$username|${remoteAddress ?: "unknown"}"
        if (username.isBlank() || username.length > 64 || password.isBlank()) {
            return AdminLoginResult.InvalidCredentials
        }
        // Brute-force: каждая попытка регистрируется лимитером (per-minute И
        // per-day = loginMaxAttempts на пару username+ip). Успешный логин
        // очищает счётчик (reset) — классическая схема brute-force защиты.
        when (loginRateLimiter.check(lockKey)) {
            is com.jarvis.server.ratelimit.RateLimitDecision.Limited ->
                return AdminLoginResult.RateLimited
            com.jarvis.server.ratelimit.RateLimitDecision.Allowed -> Unit
        }

        val account = accounts.findByUsername(username)
        val hash = account?.let { accounts.passwordHashOf(it.id) }
        if (account == null || hash == null || !AdminPasswords.verify(password, hash)) {
            return AdminLoginResult.InvalidCredentials
        }
        if (account.status != "ACTIVE") {
            return AdminLoginResult.AccountDisabled
        }
        loginRateLimiter.reset(lockKey)

        val rawToken = sessions.create(account.id, policy.sessionTtl, remoteAddress, now)
        val sessionId = sessions.findValid(rawToken, now)?.id
        return AdminLoginResult.Success(
            rawToken = rawToken,
            principal = AdminPrincipal(
                accountId = account.id,
                actor = "admin:${account.username}",
                role = account.role,
                sessionId = sessionId
            ),
            expiresAt = now.plus(policy.sessionTtl)
        )
    }

    /** Аутентификация Admin API: admin-сессия ИЛИ legacy static ADMIN-токен. */
    fun authenticate(authorizationHeader: String?, fallbackStaticAuth: Authenticator?): AdminAuthResult {
        val now = clock.instant()
        val bearer = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim().orEmpty()
        if (bearer.isNotEmpty()) {
            val session = sessions.findValid(bearer, now)
            if (session != null) {
                val account = accounts.findById(session.accountId)
                if (account == null || account.status != "ACTIVE") return AdminAuthResult.Unauthenticated
                sessions.renewIfDue(session.id, policy.sessionTtl, now)
                return AdminAuthResult.Success(
                    AdminPrincipal(
                        accountId = account.id,
                        actor = "admin:${account.username}",
                        role = account.role,
                        sessionId = session.id
                    )
                )
            }
        }
        // Legacy: статический env-токен с tier ADMIN действует как SUPER_ADMIN.
        if (fallbackStaticAuth != null && bearer.isNotEmpty()) {
            when (val result = fallbackStaticAuth.authenticate("Bearer $bearer")) {
                is AuthResult.Success if result.client.tier == ClientTier.ADMIN ->
                    return AdminAuthResult.Success(
                        AdminPrincipal(
                            accountId = null,
                            actor = "token:${result.client.clientId}",
                            role = AdminRole.SUPER_ADMIN,
                            sessionId = null
                        )
                    )
                else -> Unit
            }
        }
        return AdminAuthResult.Unauthenticated
    }

    fun logout(authorizationHeader: String?): Boolean {
        val now = clock.instant()
        val bearer = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim().orEmpty()
        if (bearer.isEmpty()) return false
        val session = sessions.findValid(bearer, now) ?: return false
        sessions.revoke(session.id, now)
        return true
    }
}
