package com.jarvis.server

import com.jarvis.server.admin.AdminAccountRepository
import com.jarvis.server.admin.AdminAuditLog
import com.jarvis.server.admin.AdminAuditQuery
import com.jarvis.server.admin.AdminHttpHandler
import com.jarvis.server.admin.AdminLoginResult
import com.jarvis.server.admin.AdminPasswords
import com.jarvis.server.admin.AdminQueries
import com.jarvis.server.admin.AdminRole
import com.jarvis.server.admin.AdminSecurityPolicy
import com.jarvis.server.admin.AdminSessionRepository
import com.jarvis.server.admin.AdminSettingsService
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * ADMIN API integration (Control Plane ТЗ §30): login/brute-force, RBAC 403,
 * dashboard из реальных данных, license lifecycle + audit, device revoke,
 * settings validate→persist→audit→apply, flags, provider key masking,
 * logout revocation. Privacy: приватный контент не запрашивается нигде.
 */
class AdminControlPlaneIntegrationTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var accounts: AdminAccountRepository
    private lateinit var sessions: AdminSessionRepository
    private lateinit var audit: AdminAuditLog
    private lateinit var settings: AdminSettingsService
    private lateinit var flags: FeatureFlagService
    private lateinit var queries: AdminQueries
    private lateinit var overrides: ProviderRuntimeOverrides
    private lateinit var handler: AdminHttpHandler
    private val staticAdminToken = "b".repeat(64)

    @Before
    fun buildHandler() {
        accounts = AdminAccountRepository(dataSource)
        sessions = AdminSessionRepository(dataSource)
        audit = AdminAuditLog(dataSource)
        settings = AdminSettingsService(dataSource, json)
        flags = FeatureFlagService(dataSource)
        queries = AdminQueries(dataSource)
        overrides = ProviderRuntimeOverrides()
        val policy = AdminSecurityPolicy()
        val staticAuth = TokenAuthenticator(mapOf(staticAdminToken to "ops-cli")) { ClientTier.ADMIN }
        handler = AdminHttpHandler(
            auth = com.jarvis.server.admin.AdminAuthService(
                accounts = accounts,
                sessions = sessions,
                loginRateLimiter = PostgresRateLimiter(
                    dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
                ),
                policy = policy
            ),
            staticAuthenticator = staticAuth,
            accounts = accounts,
            sessions = sessions,
            audit = audit,
            settings = settings,
            flags = flags,
            queries = queries,
            providerManager = mockk(relaxed = true),
            overrides = overrides,
            json = json
        )
    }

    private fun createAdmin(username: String, role: AdminRole, password: String = "bootstrap-pass-123") {
        accounts.create(username, AdminPasswords.hash(password), role, Instant.now())
    }

    private fun login(username: String, password: String): String? {
        val response = runBlocking {
            handler.handle(
                HttpRequestContext(
                    method = "POST", path = "/v1/admin/auth/login",
                    authorizationHeader = null,
                    body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                    contentLength = 0, remoteAddress = "10.0.0.9"
                )
            )
        }
        assertNotNull(response)
        if (response!!.status != 200) return null
        return Json.parseToJsonElement(response.body).let { (it as kotlinx.serialization.json.JsonObject)["token"] }
            ?.let { (it as JsonPrimitive).content }
    }

    private fun get(token: String?, path: String): com.jarvis.server.http.HttpResponseContext = runBlocking {
        handler.handle(
            HttpRequestContext(
                method = "GET", path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = "", contentLength = 0, remoteAddress = "10.0.0.9"
            )
        )!!
    }

    private fun post(token: String?, path: String, body: String = "{}"): com.jarvis.server.http.HttpResponseContext = runBlocking {
        handler.handle(
            HttpRequestContext(
                method = "POST", path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.0.0.9"
            )
        )!!
    }

    private fun put(token: String?, path: String, body: String): com.jarvis.server.http.HttpResponseContext = runBlocking {
        handler.handle(
            HttpRequestContext(
                method = "PUT", path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.0.0.9"
            )
        )!!
    }

    /* ── Authentication (ТЗ §4) ────────────────────────────────────────────── */

    @Test
    fun `login with correct credentials issues working session`() {
        createAdmin("root", AdminRole.SUPER_ADMIN)
        val token = login("root", "bootstrap-pass-123")
        assertNotNull(token)
        val me = get(token, "/v1/admin/me")
        assertEquals(200, me.status)
        assertTrue(me.body.contains("SUPER_ADMIN"))
    }

    @Test
    fun `wrong password is 401 and does not crash limiter`() {
        createAdmin("root2", AdminRole.SUPER_ADMIN)
        assertEquals(401, post(null, "/v1/admin/auth/login", "{\"username\":\"root2\",\"password\":\"" + "totally-wrong-pass" + "\"}").status)
    }

    @Test
    fun `brute force is limited after max attempts`() {
        createAdmin("victim", AdminRole.SUPER_ADMIN)
        repeat(5) {
            assertEquals(
                "attempt ${it + 1} должен быть 401",
                401,
                post(null, "/v1/admin/auth/login", "{\"username\":\"victim\",\"password\":\"" + "guess-pass-0001" + "\"}").status)
        }
        assertEquals(429, post(null, "/v1/admin/auth/login", "{\"username\":\"victim\",\"password\":\"" + "guess-pass-0002" + "\"}").status)
    }

    @Test
    fun `unauthenticated access to admin api is 401`() {
        assertEquals(401, get(null, "/v1/admin/dashboard").status)
        assertEquals(401, get("not-a-real-token", "/v1/admin/dashboard").status)
    }

    @Test
    fun `static admin token acts as super admin (legacy compat)`() {
        assertEquals(200, get(staticAdminToken, "/v1/admin/dashboard").status)
    }

    @Test
    fun `logout revokes the session`() {
        createAdmin("bye", AdminRole.ADMIN)
        val token = login("bye", "bootstrap-pass-123")!!
        assertEquals(200, get(token, "/v1/admin/me").status)
        assertEquals(200, post(token, "/v1/admin/auth/logout").status)
        assertEquals(401, get(token, "/v1/admin/me").status)
    }

    /* ── RBAC (ТЗ §5): запрещённые действия реально запрещены backend ─────── */

    @Test
    fun `viewer cannot write settings and cannot revoke devices`() {
        createAdmin("watcher", AdminRole.VIEWER)
        val token = login("watcher", "bootstrap-pass-123")!!
        assertEquals(200, get(token, "/v1/admin/dashboard").status)
        assertEquals(403, put(token, "/v1/admin/settings/system", """{"maintenanceMode":true}""").status)
        assertEquals(403, post(token, "/v1/admin/devices/${UUID.randomUUID()}/revoke").status)
        assertEquals(403, post(token, "/v1/admin/providers/GROQ/configure", """{"enabled":false}""").status)
    }

    /* ── Dashboard / health из реальных данных (ТЗ §6/§31: не mock) ────────── */

    @Test
    fun `dashboard reflects seeded data`() {
        seedPlanAndLicense()
        createAdmin("root4", AdminRole.SUPER_ADMIN)
        val superToken = login("root4", "bootstrap-pass-123")!!
        val dash = get(superToken, "/v1/admin/dashboard")
        assertEquals(200, dash.status)
        assertTrue(dash.body.contains("\"licenses\":{\"active\":1"))
        assertEquals(200, get(superToken, "/v1/admin/health").status)
    }

    /* ── Users / devices ──────────────────────────────────────────────────── */

    @Test
    fun `users list and detail expose operational metadata only`() {
        val accountId = seedPlanAndLicense()
        createAdmin("users-admin", AdminRole.SUPPORT)
        val token = login("users-admin", "bootstrap-pass-123")!!
        val list = get(token, "/v1/admin/users")
        assertEquals(200, list.status)
        assertTrue(list.body.contains(accountId.toString()))
        val detail = get(token, "/v1/admin/users/$accountId")
        assertEquals(200, detail.status)
        assertTrue(detail.body.contains("licenses"))
    }

    @Test
    fun `device revoke changes token status and writes audit`() {
        val accountId = seedPlanAndLicense()
        val tokenId = insertApiToken(accountId)
        createAdmin("dev-admin", AdminRole.ADMIN)
        val token = login("dev-admin", "bootstrap-pass-123")!!
        val response = post(token, "/v1/admin/devices/$tokenId/revoke")
        assertEquals("revoke body=${response.body}", 200, response.status)
        assertTrue("body=${response.body}", response.body.contains("REVOKED"))
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT status FROM api_tokens WHERE id = ?").use { ps ->
                ps.setObject(1, tokenId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertEquals("REVOKED", rs.getString(1))
                }
            }
        }
        assertTrue(audit.find(AdminAuditQuery(action = "device.revoke")).isNotEmpty())
    }

    /* ── License lifecycle (ТЗ §9) + audit (ТЗ §19) ───────────────────────── */

    @Test
    fun `license lifecycle actions persist and audit`() {
        val (_, licenseId) = seedPlanAndLicenseBoth()
        createAdmin("lic-admin", AdminRole.ADMIN)
        val token = login("lic-admin", "bootstrap-pass-123")!!

        val disableResp = post(token, "/v1/admin/licenses/$licenseId/disable")
        assertEquals("disable body=${disableResp.body}", 200, disableResp.status)
        assertEquals("DISABLED", statusOf(licenseId))
        assertEquals(200, post(token, "/v1/admin/licenses/$licenseId/enable").status)
        assertEquals("ACTIVE", statusOf(licenseId))
        assertEquals(200, post(token, "/v1/admin/licenses/$licenseId/extend", """{"days":30}""").status)
        assertEquals(200, post(token, "/v1/admin/licenses/$licenseId/change-plan", """{"planId":"pro_yearly"}""").status)
        assertEquals("pro_yearly", planOf(licenseId))

        val actions = audit.find(AdminAuditQuery(entityType = "LICENSE")).map { it.action }
        assertTrue(actions.containsAll(listOf("license.disable", "license.enable", "license.extend", "license.change-plan")))
        // Audit-only чтение: попыток мутаций нет в route (audit endpoint только GET).
        assertEquals(404, post(token, "/v1/admin/audit").status)
        assertEquals(404, put(token, "/v1/admin/audit/x", "{}").status)
    }

    @Test
    fun `change plan with unknown plan is 400 and audited as nothing`() {
        val (_, licenseId) = seedPlanAndLicenseBoth()
        createAdmin("plan-admin", AdminRole.ADMIN)
        val token = login("plan-admin", "bootstrap-pass-123")!!
        val response = post(token, "/v1/admin/licenses/$licenseId/change-plan", """{"planId":"no_such_plan"}""")
        assertEquals("change-plan body=${response.body}", 400, response.status)
        assertTrue(audit.find(AdminAuditQuery(action = "license.change-plan")).isEmpty())
    }

    /* ── Settings: validate → persist → audit → apply (ТЗ §12/§20) ────────── */

    @Test
    fun `ai settings update applies runtime overrides and validates input`() {
        createAdmin("ai-admin", AdminRole.ADMIN)
        val token = login("ai-admin", "bootstrap-pass-123")!!
        assertEquals(200, put(token, "/v1/admin/settings/ai", """{"localFirstEnabled":true,"cloudEscalationEnabled":true,"providers":{"GROQ":{"enabled":false,"priority":1}}}""").status)
        assertFalse("GROQ должен быть выключен в рантайме", overrides.enabled(com.jarvis.server.provider.ProviderId.GROQ) ?: true)
        assertEquals(1, overrides.priority(com.jarvis.server.provider.ProviderId.GROQ))
        // Валидация: неизвестный провайдер → 400, и nothing applied.
        assertEquals(400, put(token, "/v1/admin/settings/ai", """{"providers":{"OPENAI":{"enabled":false}}}""").status)
        assertFalse(overrides.enabled(com.jarvis.server.provider.ProviderId.GROQ) == true)
        assertTrue(audit.find(AdminAuditQuery(action = "settings.update")).isNotEmpty())
    }

    @Test
    fun `cost settings persist and feed cost endpoint`() {
        createAdmin("cost-admin", AdminRole.ADMIN)
        val token = login("cost-admin", "bootstrap-pass-123")!!
        assertEquals(
            200,
            put(token, "/v1/admin/settings/cost", """{"providers":{"GROQ":{"usdPerMillionInput":5.0,"usdPerMillionOutput":10.0}}}""").status
        )
        assertEquals(200, get(token, "/v1/admin/settings/cost").status)
    }

    /* ── Feature flags (ТЗ §21) ───────────────────────────────────────────── */

    @Test
    fun `feature flags persist with rollout and audit`() {
        createAdmin("flag-admin", AdminRole.ADMIN)
        val token = login("flag-admin", "bootstrap-pass-123")!!
        assertEquals(200, put(token, "/v1/admin/features/wake_word", """{"enabled":true,"rolloutPercent":25}""").status)
        assertEquals(200, get(token, "/v1/admin/features").status)
        val flag = flags.get("wake_word")!!
        assertTrue(flag.enabled)
        assertEquals(25, flag.rolloutPercent)
        assertTrue(audit.find(AdminAuditQuery(action = "feature.update")).isNotEmpty())
        // Детерминированный rollout.
        assertEquals(flags.isEnabledFor("wake_word", "dev-42"), flags.isEnabledFor("wake_word", "dev-42"))
    }

    /* ── Providers: секреты никогда не возвращаются (ТЗ §13) ──────────────── */

    @Test
    fun `provider list masks api keys`() {
        createAdmin("prov-admin", AdminRole.SUPPORT)
        val token = login("prov-admin", "bootstrap-pass-123")!!
        val response = get(token, "/v1/admin/providers")
        assertEquals(200, response.status)
        assertTrue(response.body.contains("••••"))
        // Если ключ когда-нибудь попадёт в конфиг как секрет — он не должен утечь.
        assertFalse(response.body.contains("sk-"))
    }

    /* ── Usage / cost из реальных записей (ТЗ §15/§16/§31) ────────────────── */

    @Test
    fun `usage and cost endpoints aggregate real ai_usage_records`() {
        insertUsage(clientId = "11111111-1111-1111-1111-111111111111", provider = "GROQ", inputTokens = 1_000_000, outputTokens = 1_000_000, success = true)
        createAdmin("usage-admin", AdminRole.VIEWER)
        val token = login("usage-admin", "bootstrap-pass-123")!!
        val usage = get(token, "/v1/admin/usage?days=7")
        assertEquals("usage body=${usage.body}", 200, usage.status)
        assertTrue("usage body=${usage.body}", usage.body.contains("\"requests\":1"))
        assertTrue(usage.body.contains("NOT COLLECTED")) // локальные выполнения: честно

        // VIEWER не может писать настройки → cost настраивает отдельный админ:
        createAdmin("cost-admin2", AdminRole.ADMIN)
        val adminToken = login("cost-admin2", "bootstrap-pass-123")!!
        put(adminToken, "/v1/admin/settings/cost", """{"providers":{"GROQ":{"usdPerMillionInput":5.0,"usdPerMillionOutput":10.0}}}""")
        val cost = get(token, "/v1/admin/usage/cost?days=7")
        assertEquals(200, cost.status)
        assertTrue("cost body=${cost.body}", cost.body.contains("in/1e6*5.0"))
        assertTrue(cost.body.contains("\"totalUsd\":15.0") || cost.body.contains("15.0"))
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private var seedReturn: Pair<UUID, UUID> = UUID.randomUUID() to UUID.randomUUID()

    private fun seedPlanAndLicense(): UUID {
        seedPlanAndLicenseBoth()
        return seedReturn.first
    }

    private fun seedPlanAndLicenseBoth(): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val licenseId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        seedReturn = accountId to licenseId
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, accountId); ps.setString(2, "user-$accountId"); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate()
            }
            listOf("pro_monthly", "pro_yearly").forEach { plan ->
                c.prepareStatement(
                    "INSERT INTO billing_plans (id, product_id, display_name, duration_days, amount_minor, currency, active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 30, 990, 'USD', TRUE, ?, ?) ON CONFLICT (id) DO NOTHING"
                ).use { ps ->
                    ps.setString(1, plan); ps.setString(2, plan); ps.setString(3, plan); ps.setTimestamp(4, now); ps.setTimestamp(5, now); ps.executeUpdate()
                }
            }
            c.prepareStatement(
                "INSERT INTO licenses (id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at, product_id, plan_id, account_id, " +
                    "one_time, redeemed_at, metadata, created_at, updated_at) " +
                    "VALUES (?, decode('aabb', 'hex'), 'AA', 'ACTIVE', 'PAID', ?, now() - interval '1 day', now() + interval '30 days', 'jarvis', 'pro_monthly', ?, " +
                    "TRUE, now() - interval '1 day', '{}'::jsonb, ?, ?)"
            ).use { ps ->
                ps.setObject(1, licenseId); ps.setTimestamp(2, now); ps.setObject(3, accountId); ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
        }
        return accountId to licenseId
    }

    private fun insertApiToken(accountId: UUID): UUID {
        val tokenId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO api_tokens (id, account_id, token_hash, status, issued_at, created_at) " +
                    "VALUES (?, ?, decode('deadbeef', 'hex'), 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, tokenId); ps.setObject(2, accountId); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate()
            }
        }
        return tokenId
    }

    private fun insertUsage(clientId: String, provider: String, inputTokens: Long, outputTokens: Long, success: Boolean) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO ai_usage_records (request_id, client_id, provider, model, latency_ms, input_tokens, output_tokens, total_tokens, " +
                    "success, prompt_chars, response_chars, occurred_at) " +
                    "VALUES (?, ?, ?, 'test-model', 120, ?, ?, ?, ?, 10, 20, now())"
            ).use { ps ->
                ps.setString(1, "req-" + UUID.randomUUID()); ps.setString(2, clientId); ps.setString(3, provider)
                ps.setLong(4, inputTokens); ps.setLong(5, outputTokens); ps.setLong(6, inputTokens + outputTokens)
                ps.setBoolean(7, success); ps.executeUpdate()
            }
        }
    }

    private fun statusOf(licenseId: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status FROM licenses WHERE id = ?").use { ps ->
            ps.setObject(1, licenseId)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    private fun planOf(licenseId: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("SELECT plan_id FROM licenses WHERE id = ?").use { ps ->
            ps.setObject(1, licenseId)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }
}
