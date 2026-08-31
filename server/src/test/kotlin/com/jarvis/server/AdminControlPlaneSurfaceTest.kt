package com.jarvis.server

import com.jarvis.server.admin.AdminAccountRepository
import com.jarvis.server.admin.AdminAuditLog
import com.jarvis.server.admin.AdminAuthService
import com.jarvis.server.admin.AdminHttpHandler
import com.jarvis.server.admin.AdminPasswords
import com.jarvis.server.admin.AdminQueries
import com.jarvis.server.admin.AdminRole
import com.jarvis.server.admin.AdminSecurityPolicy
import com.jarvis.server.admin.AdminSessionRepository
import com.jarvis.server.admin.AdminSettingsService
import com.jarvis.server.admin.AdminUiHandler
import com.jarvis.server.admin.CostSettings
import com.jarvis.server.admin.FeatureFlagService
import com.jarvis.server.admin.ProviderCostEntry
import com.jarvis.server.admin.ProviderRuntimeOverrides
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.ratelimit.PostgresRateLimiter
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
 * Full READ-SURFACE + operator management + UI page sweep (coverage + E2E §30):
 * Admin Login → Dashboard → User → Device → License → Setting change → Audit.
 */
class AdminControlPlaneSurfaceTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var accounts: AdminAccountRepository
    private lateinit var sessions: AdminSessionRepository
    private lateinit var settings: AdminSettingsService
    private lateinit var handler: AdminHttpHandler
    private lateinit var ui: AdminUiHandler

    @Before
    fun build() {
        accounts = AdminAccountRepository(dataSource)
        sessions = AdminSessionRepository(dataSource)
        settings = AdminSettingsService(dataSource, json)
        val policy = AdminSecurityPolicy()
        val staticAuth = TokenAuthenticator(mapOf("d".repeat(64) to "ops")) { ClientTier.ADMIN }
        val auth = AdminAuthService(
            accounts = accounts,
            sessions = sessions,
            loginRateLimiter = PostgresRateLimiter(
                dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
            ),
            policy = policy
        )
        val queries = AdminQueries(dataSource)
        val flags = FeatureFlagService(dataSource)
        ui = AdminUiHandler(
            auth = auth, staticAuthenticator = staticAuth, audit = AdminAuditLog(dataSource),
            settings = settings, flags = flags, queries = queries,
            providerManager = mockk(relaxed = true), json = json
        )
        handler = AdminHttpHandler(
            auth = auth, staticAuthenticator = staticAuth,
            accounts = accounts, sessions = sessions,
            audit = AdminAuditLog(dataSource), settings = settings, flags = flags,
            queries = queries, providerManager = mockk(relaxed = true),
            overrides = ProviderRuntimeOverrides(), ui = ui, json = json
        )
    }

    private fun api(method: String, path: String, token: String?, body: String = "") = runBlocking {
        handler.handle(
            HttpRequestContext(
                method = method, path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.9.9.1"
            )
        )!!
    }

    private fun login(username: String, password: String): String {
        val r = api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        assertEquals(200, r.status)
        return Json.parseToJsonElement(r.body).let { (it as kotlinx.serialization.json.JsonObject)["token"] }
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }!!
    }

    private fun page(method: String, path: String, cookie: String? = null, body: String = "") = runBlocking {
        ui.handle(
            HttpRequestContext(
                method = method, path = path, authorizationHeader = null, body = body,
                contentLength = body.length.toLong(),
                headers = if (cookie != null) mapOf("Cookie" to cookie) else emptyMap(),
                remoteAddress = "10.9.9.2"
            )
        )
    }

    @Test
    fun `operator management lifecycle is gated and audited`() {
        accounts.create("super1", AdminPasswords.hash("super-pass-12345"), AdminRole.SUPER_ADMIN, Instant.now())
        val token = login("super1", "super-pass-12345")

        // Создание SUPPORT-оператора.
        val created = api(
            "POST", "/v1/admin/admins", token,
            "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\",\"role\":\"SUPPORT\"}"
        )
        assertEquals("create body=${created.body}", 200, created.status)
        // VIEWER не может создавать операторов.
        accounts.create("seer", AdminPasswords.hash("viewer-pass-123"), AdminRole.VIEWER, Instant.now())
        val viewerToken = login("seer", "viewer-pass-123")
        assertEquals(403, api("POST", "/v1/admin/admins", viewerToken, "{\"username\":\"" + "x1" + "\",\"password\":\"" + "whatever-pass-1" + "\",\"role\":\"ADMIN\"}").status)
        // Список и смена статуса.
        assertEquals(200, api("GET", "/v1/admin/admins", token).status)
        val supporterId = accounts.findByUsername("supporter")!!.id
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-status", token, """{"status":"DISABLED"}""").status)
        // DISABLED-оператор не может войти.
        assertEquals(403, api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\"}").status)
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-status", token, """{"status":"ACTIVE"}""").status)
        // Ротация пароля + старый пароль больше не работает.
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-password", token, "{\"password\":\"" + "fresh-pass-12345" + "\"}").status)
        val relogin = api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\"}")
        assertEquals(401, relogin.status)
        assertEquals(200, api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "fresh-pass-12345" + "\"}").status)
        // Дубликат username → 409.
        assertEquals(409, api("POST", "/v1/admin/admins", token, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "another-pass-123" + "\",\"role\":\"VIEWER\"}").status)
    }

    @Test
    fun `full read surface returns 200 over seeded data`() {
        val (accountId, licenseId) = seedBoth()
        seedOrder(accountId)
        seedUsage()
        accounts.create("reader", AdminPasswords.hash("reader-pass-12345"), AdminRole.ADMIN, Instant.now())
        val token = login("reader", "reader-pass-12345")

        assertEquals(200, api("GET", "/v1/admin/subscriptions", token).status)
        assertEquals(200, api("GET", "/v1/admin/devices", token).status)
        assertEquals(200, api("GET", "/v1/admin/licenses", token).status)
        assertEquals(200, api("GET", "/v1/admin/licenses/$licenseId", token).status)
        val devices = api("GET", "/v1/admin/devices", token)
        assertTrue(devices.body.contains(accountId.toString()))
        // Реальные записи в logs (cloud) и audit (действий пока нет → пусто, но 200).
        assertEquals(200, api("GET", "/v1/admin/logs?component=CLOUD", token).status)
        assertEquals(200, api("GET", "/v1/admin/audit", token).status)
        assertEquals(200, api("GET", "/v1/admin/users/$accountId", token).status)
        assertEquals(200, api("GET", "/v1/admin/dashboard", token).status)
        // Settings persistence: новый сервис должен прочитать сохранённое из БД.
        assertEquals(
            200,
            api("PUT", "/v1/admin/settings/cost", token, """{"providers":{"GEMINI":{"usdPerMillionInput":2.5,"usdPerMillionOutput":7.5}}}""").status
        )
        val reloaded = AdminSettingsService(dataSource, json)
        val reloadedCosts = reloaded.cost().providers["GEMINI"]
        assertEquals(2.5, reloadedCosts?.usdPerMillionInput!!, 1e-9)
        // Session housekeeping.
        sessions.purge(java.time.Duration.ofDays(1), Instant.now())
    }

    @Test
    fun `ui exposes every operational page over seeded data`() {
        val (accountId, licenseId) = seedBoth()
        seedOrder(accountId)
        seedUsage()
        accounts.create("pager", AdminPasswords.hash("pager-pass-12345"), AdminRole.ADMIN, Instant.now())
        val token = login("pager", "pager-pass-12345")
        val cookie = "admin_session=$token"

        assertEquals(200, page("GET", "/v1/admin/ui/dashboard", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/users", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/users/$accountId", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/devices", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/licenses", cookie).status)
        val licensePage = page("GET", "/v1/admin/ui/licenses/$licenseId", cookie)
        assertEquals(200, licensePage.status)
        assertTrue("форма действий должна быть у ADMIN", licensePage.body.contains("Suspend"))
        assertEquals(200, page("GET", "/v1/admin/ui/providers", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/usage", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/logs", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/audit", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/settings", cookie).status)
        assertEquals(200, page("GET", "/v1/admin/ui/flags", cookie).status)
        // CSRF: мутация без токена → 403.
        assertEquals(403, page("POST", "/v1/admin/ui/licenses/$licenseId", cookie, body = "action=disable").status)
        // UI logout: cookie больше не даёт доступ.
        assertEquals(303, page("POST", "/v1/admin/ui/logout", cookie).status)
        val after = page("GET", "/v1/admin/ui/dashboard", cookie)
        assertEquals(303, after.status)
    }

    /* ── seeds ─────────────────────────────────────────────────────────────── */

    private fun seedBoth(): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val licenseId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)").use { ps ->
                ps.setObject(1, accountId); ps.setString(2, "acc-$accountId"); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO billing_plans (id, product_id, display_name, duration_days, amount_minor, currency, active, created_at, updated_at) " +
                    "VALUES ('pro_monthly','pro_monthly','Pro',30,990,'USD',TRUE,?,?) ON CONFLICT (id) DO NOTHING"
            ).use { ps -> ps.setTimestamp(1, now); ps.setTimestamp(2, now); ps.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO licenses (id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at, product_id, plan_id, account_id, " +
                    "one_time, redeemed_at, metadata, created_at, updated_at) " +
                    "VALUES (?, decode('beef', 'hex'), 'BE', 'ACTIVE', 'PAID', ?, now() - interval '1 day', now() + interval '30 days', 'jarvis', 'pro_monthly', ?, " +
                    "TRUE, now() - interval '1 day', '{}'::jsonb, ?, ?)"
            ).use { ps ->
                ps.setObject(1, licenseId); ps.setTimestamp(2, now); ps.setObject(3, accountId); ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO api_tokens (id, account_id, token_hash, status, issued_at, created_at) VALUES (?, ?, decode('c0ffee', 'hex'), 'ACTIVE', ?, ?)"
            ).use { ps -> ps.setObject(1, UUID.randomUUID()); ps.setObject(2, accountId); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate() }
        }
        return accountId to licenseId
    }

    private fun seedOrder(accountId: UUID) {
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO billing_orders (id, account_id, plan_id, provider, status, amount_minor, currency, idempotency_key, paid_at, created_at, updated_at) " +
                    "VALUES (?, ?, 'pro_monthly', 'PADDLE', 'PAID', 1400, 'USD', ?, now(), ?, ?)"
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID()); ps.setObject(2, accountId)
                ps.setString(3, "idem-" + UUID.randomUUID()); ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
        }
    }

    private fun seedUsage() {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO ai_usage_records (request_id, client_id, provider, model, latency_ms, input_tokens, output_tokens, total_tokens, " +
                    "success, prompt_chars, response_chars, occurred_at) VALUES (?, '22222222-2222-2222-2222-222222222222', 'GEMINI', 'm', 90, 500, 250, 750, TRUE, 11, 22, now())"
            ).use { ps -> ps.setString(1, "req-" + UUID.randomUUID()); ps.executeUpdate() }
        }
    }
}
