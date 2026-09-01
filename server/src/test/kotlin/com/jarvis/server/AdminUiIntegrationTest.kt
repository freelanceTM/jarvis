package com.jarvis.server

import com.jarvis.server.admin.AdminAccountRepository
import com.jarvis.server.admin.AdminAuditLog
import com.jarvis.server.admin.AdminAuthService
import com.jarvis.server.admin.AdminPasswords
import com.jarvis.server.admin.AdminQueries
import com.jarvis.server.admin.AdminRole
import com.jarvis.server.admin.AdminSecurityPolicy
import com.jarvis.server.admin.AdminSessionRepository
import com.jarvis.server.admin.AdminSettingsService
import com.jarvis.server.admin.AdminUiHandler
import com.jarvis.server.admin.FeatureFlagService
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.provider.ProviderManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * UI integration (Control Plane ТЗ §30 E2E): login → dashboard → pages.
 * Cookie-сессии HttpOnly SameSite=Strict; формы защищены CSRF.
 */
class AdminUiIntegrationTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var accounts: AdminAccountRepository
    private lateinit var ui: AdminUiHandler

    @Before
    fun buildUi() {
        accounts = AdminAccountRepository(dataSource)
        val policy = AdminSecurityPolicy()
        val staticAuth = TokenAuthenticator(mapOf("c".repeat(64) to "ops")) { ClientTier.ADMIN }
        val auth = AdminAuthService(
            accounts = accounts,
            sessions = AdminSessionRepository(dataSource),
            loginRateLimiter = PostgresRateLimiter(
                dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
            ),
            policy = policy
        )
        ui = AdminUiHandler(
            auth = auth,
            staticAuthenticator = staticAuth,
            audit = AdminAuditLog(dataSource),
            settings = AdminSettingsService(dataSource, json),
            flags = FeatureFlagService(dataSource),
            queries = AdminQueries(dataSource),
            providerManager = mockk(relaxed = true),
            json = json
        )
    }

    private fun call(method: String, path: String, body: String = "", cookie: String? = null) = runBlocking {
        ui.handle(
            HttpRequestContext(
                method = method, path = path, authorizationHeader = null,
                body = body, contentLength = body.length.toLong(),
                headers = if (cookie != null) mapOf("Cookie" to cookie) else emptyMap(),
                remoteAddress = "10.1.1.5"
            )
        )
    }

    @Test
    fun `login page renders and unauthenticated dashboard redirects`() {
        val login = call("GET", "/v1/admin/ui/login")
        assertEquals(200, login.status)
        assertTrue(login.body.contains("OMNIX Control Plane"))
        val dash = call("GET", "/v1/admin/ui/dashboard")
        assertEquals(303, dash.status)
        assertEquals("/v1/admin/ui/login", dash.headers["Location"])
    }

    @Test
    fun `successful form login sets httponly cookie and dashboard renders real data`() {
        accounts.create("ui-root", AdminPasswords.hash("ui-password-123"), AdminRole.ADMIN, java.time.Instant.now())
        val response = call(
            "POST", "/v1/admin/ui/login",
            body = "username=ui-root&password=" + "ui-password-123"
        )
        assertEquals(303, response.status)
        val cookie = response.headers["Set-Cookie"]!!
        assertTrue(cookie.startsWith("admin_session="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        val sessionToken = cookie.substringAfter("admin_session=").substringBefore(';')

        val dash = call("GET", "/v1/admin/ui/dashboard", cookie = "admin_session=$sessionToken")
        assertEquals(200, dash.status)
        assertTrue(dash.body.contains("Dashboard"))
        assertTrue(dash.body.contains("NOT COLLECTED")) // честный локальный KPI

        val users = call("GET", "/v1/admin/ui/users", cookie = "admin_session=$sessionToken")
        assertEquals(200, users.status)

        val audit = call("GET", "/v1/admin/ui/audit", cookie = "admin_session=$sessionToken")
        assertEquals(200, audit.status)
    }

    @Test
    fun `user-controlled values are escaped in rendered html`() {
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, accountId)
                ps.setString(2, "<img src=x onerror=alert(1)>")
                ps.setTimestamp(3, now)
                ps.setTimestamp(4, now)
                ps.executeUpdate()
            }
        }
        accounts.create("ui-escape", AdminPasswords.hash("ui-password-123"), AdminRole.VIEWER, Instant.now())
        val tokenResponse = call(
            "POST", "/v1/admin/ui/login",
            body = "username=ui-escape&password=ui-password-123"
        )
        val sessionToken = tokenResponse.headers["Set-Cookie"]!!
            .substringAfter("admin_session=").substringBefore(';')

        val users = call("GET", "/v1/admin/ui/users", cookie = "admin_session=$sessionToken")
        assertEquals(200, users.status)
        assertTrue(users.body.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(!users.body.contains("<img src=x onerror=alert(1)>"))
    }

    @Test
    fun `wrong password stays on login page`() {
        accounts.create("ui-root2", AdminPasswords.hash("ui-password-123"), AdminRole.VIEWER, java.time.Instant.now())
        val response = call("POST", "/v1/admin/ui/login", body = "username=ui-root2&password=" + "wrong-pass-999999")
        assertEquals(200, response.status)
        assertTrue(response.body.contains("Неверные"))
    }
}
