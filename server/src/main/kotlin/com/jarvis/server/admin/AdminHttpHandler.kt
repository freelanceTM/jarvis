package com.jarvis.server.admin

import com.jarvis.server.auth.Authenticator
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.HttpResponseContext
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * OMNIX CONTROL PLANE — Admin API (Control Plane ТЗ §23).
 *
 * Все маршруты под существующим convention `/v1/admin/…` (ТЗ §23: «сначала
 * проверь существующие routes»); issue/revoke лицензий остаются в
 * LicenseBillingHttpHandler — этот handler возвращает для них null.
 *
 * Flow каждого запроса: Authentication (admin session или legacy static
 * ADMIN-токен) → RBAC ([AdminRbac], backend-only) → business logic →
 * (для мутаций) immutable audit. Frontend-authorization не признаётся.
 *
 * Секреты (API-ключи провайдеров) НИКОГДА не покидают сервер: провайдеры
 * отдаются с масками вида `••••ABCD`.
 */
class AdminHttpHandler(
    private val auth: AdminAuthService,
    private val staticAuthenticator: Authenticator?,
    private val accounts: AdminAccountRepository,
    private val sessions: AdminSessionRepository,
    private val audit: AdminAuditLog,
    private val settings: AdminSettingsService,
    private val flags: FeatureFlagService,
    private val queries: AdminQueries,
    private val providerManager: ProviderManager,
    private val overrides: com.jarvis.server.admin.ProviderRuntimeOverrides,
    /** Server-rendered operational UI (/v1/admin/ui/…). */
    private val ui: com.jarvis.server.admin.AdminUiHandler? = null,
    private val json: Json = Json
) {

    companion object {
        private const val PREFIX = "/v1/admin/"
        private val RESERVED_FOR_LICENSE_HANDLER = setOf(
            "/v1/admin/licenses/issue", "/v1/admin/licenses/revoke"
        )
    }

    suspend fun handle(request: HttpRequestContext): HttpResponseContext? {
        // path может прийти и с query (в тестах/прямых вызовах) — режем её.
        val path = request.path.substringBefore('?')
        if (!path.startsWith(PREFIX) || path in RESERVED_FOR_LICENSE_HANDLER) return null
        val sub = path.removePrefix(PREFIX).trim('/')

        // UI-страницы живут в собственном cookie-flow (login до аутентификации).
        if (sub.startsWith("ui") && ui != null) return ui.handle(request)

        return try {
            route(request, sub)
                ?: plain(404, buildJsonObject { put("error", "not_found") })
        } catch (e: SettingsValidationError) {
            plain(400, buildJsonObject { put("error", e.message ?: "invalid_settings") })
        } catch (e: AdminForbiddenException) {
            plain(403, buildJsonObject { put("error", e.message ?: "forbidden") })
        }
    }

    /** Спец-исключение с конкретным сообщением (валидация настроек). */
    class SettingsValidationError(override val message: String) : Exception(message)

    class AdminForbiddenException(override val message: String) : Exception(message)

    /* ── routing ──────────────────────────────────────────────────────────── */

    private fun route(request: HttpRequestContext, sub: String): HttpResponseContext? {
        val parts = sub.split('/').filter { it.isNotEmpty() }
        val method = request.method

        // Аутентификация. login — единственный публичный маршрут.
        if (parts.size == 2 && parts[0] == "auth") {
            return when {
                parts[1] == "login" && method == "POST" -> handleLogin(request)
                parts[1] == "logout" && method == "POST" -> handleLogout(request)
                else -> null
            }
        }
        val principal = auth.authenticate(request.authorizationHeader, staticAuthenticator)
            .let { when (it) {
                is AdminAuthResult.Success -> it.principal
                AdminAuthResult.Unauthenticated ->
                    return plain(401, buildJsonObject { put("error", "unauthenticated") })
            } }

        return when {
            parts.size == 1 && parts[0] == "me" && method == "GET" -> handleMe(principal)
            parts.size == 1 && parts[0] == "dashboard" && method == "GET" ->
                requirePermission(principal, AdminPermission.DASHBOARD_READ) { handleDashboard() }
            parts.size == 1 && parts[0] == "health" && method == "GET" ->
                requirePermission(principal, AdminPermission.DASHBOARD_READ) { handleHealth() }
            parts.size == 1 && parts[0] == "users" && method == "GET" ->
                requirePermission(principal, AdminPermission.USERS_READ) { handleUsers(request) }
            parts.size == 2 && parts[0] == "users" && method == "GET" ->
                requirePermission(principal, AdminPermission.USERS_READ) { handleUserDetail(parts[1]) }
            parts.size == 1 && parts[0] == "devices" && method == "GET" ->
                requirePermission(principal, AdminPermission.DEVICES_READ) { handleDevices(request) }
            parts.size == 2 && parts[0] == "devices" && method == "GET" ->
                requirePermission(principal, AdminPermission.DEVICES_READ) { handleDevice(parts[1]) }
            parts.size == 3 && parts[0] == "devices" && parts[2] == "revoke" && method == "POST" ->
                requirePermission(principal, AdminPermission.DEVICES_REVOKE) { handleDeviceRevoke(principal, request, parts[1]) }
            parts.size == 1 && parts[0] == "licenses" && method == "GET" ->
                requirePermission(principal, AdminPermission.LICENSES_READ) { handleLicenses(request) }
            parts.size == 2 && parts[0] == "licenses" && method == "GET" ->
                requirePermission(principal, AdminPermission.LICENSES_READ) { handleLicense(parts[1]) }
            parts.size == 3 && parts[0] == "licenses" && method == "POST" ->
                requirePermission(principal, AdminPermission.LICENSES_WRITE) { handleLicenseAction(principal, request, parts[1], parts[2]) }
            parts.size == 1 && parts[0] == "subscriptions" && method == "GET" ->
                requirePermission(principal, AdminPermission.SUBSCRIPTIONS_READ) { handleSubscriptions(request) }
            parts.size == 1 && parts[0] == "providers" && method == "GET" ->
                requirePermission(principal, AdminPermission.PROVIDERS_READ) { handleProviders() }
            parts.size == 3 && parts[0] == "providers" && parts[2] == "configure" && method == "POST" ->
                requirePermission(principal, AdminPermission.PROVIDERS_CONFIGURE) { handleProviderConfigure(principal, request, parts[1]) }
            parts.size == 1 && parts[0] == "usage" && method == "GET" ->
                requirePermission(principal, AdminPermission.USAGE_READ) { handleUsage(request) }
            parts.size == 2 && parts[0] == "usage" && method == "GET" ->
                requirePermission(principal, AdminPermission.USAGE_READ) { handleUsageCost(request, parts[1]) }
            parts.size == 1 && parts[0] == "logs" && method == "GET" ->
                requirePermission(principal, AdminPermission.LOGS_READ) { handleLogs(request) }
            parts.size == 1 && parts[0] == "audit" && method == "GET" ->
                requirePermission(principal, AdminPermission.AUDIT_READ) { handleAudit(request) }
            parts.size == 2 && parts[0] == "settings" && method == "GET" ->
                requirePermission(principal, AdminPermission.SETTINGS_READ) { handleGetSettings(parts[1]) }
            parts.size == 2 && parts[0] == "settings" && method == "PUT" ->
                requirePermission(principal, AdminPermission.SETTINGS_WRITE) { handlePutSettings(principal, request, parts[1]) }
            parts.size == 1 && parts[0] == "admins" && method == "GET" ->
                requirePermission(principal, AdminPermission.ADMINS_MANAGE) { handleAdmins() }
            parts.size == 1 && parts[0] == "admins" && method == "POST" ->
                requirePermission(principal, AdminPermission.ADMINS_MANAGE) { handleAdminCreate(principal, request) }
            parts.size == 3 && parts[0] == "admins" && parts[2] == "set-status" && method == "POST" ->
                requirePermission(principal, AdminPermission.ADMINS_MANAGE) { handleAdminSetStatus(principal, request, parts[1]) }
            parts.size == 3 && parts[0] == "admins" && parts[2] == "set-password" && method == "POST" ->
                requirePermission(principal, AdminPermission.ADMINS_MANAGE) { handleAdminSetPassword(principal, request, parts[1]) }
            parts.size == 1 && parts[0] == "features" && method == "GET" ->
                requirePermission(principal, AdminPermission.FEATURES_READ) { handleFlags() }
            parts.size == 2 && parts[0] == "features" && method == "PUT" ->
                requirePermission(principal, AdminPermission.FEATURES_WRITE) { handlePutFlag(principal, request, parts[1]) }
            else -> null // не наш маршрут (или 405 для нашего пути) — отдаём наверх
        }
    }

    private inline fun requirePermission(
        principal: AdminPrincipal,
        permission: AdminPermission,
        block: () -> HttpResponseContext
    ): HttpResponseContext {
        if (!AdminRbac.can(principal.role, permission)) {
            throw AdminForbiddenException("role ${principal.role} lacks $permission")
        }
        return block()
    }

    /* ── handlers: auth ───────────────────────────────────────────────────── */

    private fun bodyJson(request: HttpRequestContext): JsonObject? =
        runCatching { json.parseToJsonElement(request.body).let { it as? JsonObject } }.getOrNull()

    private fun handleLogin(request: HttpRequestContext): HttpResponseContext {
        val body = bodyJson(request)
        val username = body?.get("username")?.let { (it as? JsonPrimitive)?.content } ?: ""
        val password = body?.get("password")?.let { (it as? JsonPrimitive)?.content } ?: ""
        return when (val result = auth.login(username, password, request.remoteAddress)) {
            is AdminLoginResult.Success -> {
                audit.append(
                    actor = result.principal.actor, action = "admin.login", entityType = "ADMIN_SESSION",
                    entityId = result.principal.sessionId?.toString(), oldValue = "{}", newValue = "{}",
                    remoteAddress = request.remoteAddress, sessionId = result.principal.sessionId,
                    requestId = null
                )
                plain(
                    200, buildJsonObject {
                        put("token", result.rawToken)
                        put("role", result.principal.role.name)
                        put("actor", result.principal.actor)
                        put("expiresAt", result.expiresAt.toString())
                    }
                )
            }
            AdminLoginResult.InvalidCredentials -> plain(401, buildJsonObject { put("error", "invalid_credentials") })
            AdminLoginResult.AccountDisabled -> plain(403, buildJsonObject { put("error", "account_disabled") })
            AdminLoginResult.RateLimited -> plain(429, buildJsonObject { put("error", "rate_limited") })
        }
    }

    private fun handleLogout(request: HttpRequestContext): HttpResponseContext {
        val done = auth.logout(request.authorizationHeader)
        return plain(200, buildJsonObject { put("loggedOut", done) })
    }

    private fun handleMe(principal: AdminPrincipal): HttpResponseContext =
        plain(
            200, buildJsonObject {
                put("actor", principal.actor)
                put("role", principal.role.name)
                putJsonArray("permissions") {
                    AdminRbac.permissionsOf(principal.role).forEach { add(JsonPrimitive(it.name)) }
                }
            }
        )

    /* ── handlers: dashboard & health ─────────────────────────────────────── */

    private fun handleDashboard(): HttpResponseContext {
        val snap = queries.dashboard()
        val providerHealth = providerManager.healthSnapshot()
        return plain(
            200, buildJsonObject {
                putJsonObject("users") { put("total", snap.accountsTotal) }
                putJsonObject("licenses") {
                    put("active", snap.licensesActive)
                    put("issued", snap.licensesIssued)
                }
                putJsonObject("devices") { put("tokensActive", snap.tokensActive) }
                putJsonObject("requests") {
                    put("today", snap.requestsToday)
                    put("errorsToday", snap.errorsToday)
                    put("tokensToday", snap.tokensToday)
                }
                putJsonObject("billing") { put("ordersPending", snap.ordersPending) }
                putJsonObject("providers") {
                    providerHealth.forEach { (id, snapshot) ->
                        putJsonObject(id.name) {
                            put("status", snapshot.status.name)
                            put("circuit", snapshot.circuitState.name)
                        }
                    }
                }
                put("localExecutionRate", "NOT COLLECTED")
            }
        )
    }

    private fun handleHealth(): HttpResponseContext {
        val dbOk = runCatching { queries.dashboard().accountsTotal }.isSuccess
        val providerHealth = providerManager.healthSnapshot()
        return plain(
            200, buildJsonObject {
                putJsonObject("api") { put("status", "OK") }
                putJsonObject("database") { put("status", if (dbOk) "OK" else "DOWN") }
                putJsonObject("aiGateway") { put("status", "OK") }
                putJsonObject("authentication") { put("status", "OK") }
                putJsonObject("licenseService") { put("status", "OK") }
                putJsonObject("providers") {
                    providerHealth.forEach { (id, snapshot) ->
                        putJsonObject(id.name) {
                            put("status", snapshot.status.name)
                            put("circuit", snapshot.circuitState.name)
                            snapshot.permanentReason?.let { put("permanentReason", it) }
                        }
                    }
                }
            }
        )
    }

    /* ── handlers: users / devices ────────────────────────────────────────── */

    private fun pagination(request: HttpRequestContext): Pair<Int, Long> {
        val page = request.header("X-Page")?.toIntOrNull() ?: queryParam(request, "page")?.toIntOrNull() ?: 0
        val size = queryParam(request, "size")?.toIntOrNull() ?: 50
        return size to (page.toLong() * size)
    }

    private fun queryParam(request: HttpRequestContext, name: String): String? {
        val query = request.rawQuery ?: request.path.substringAfter('?', "").takeIf { request.path.contains('?') }
        return query?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
    }

    private fun handleUsers(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        val rows = queries.users(queryParam(request, "q"), size, offset)
        return plain(
            200, buildJsonObject {
                putJsonArray("users") {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row.accountId.toString())
                                put("externalRef", row.externalRef)
                                put("status", row.status)
                                put("createdAt", row.createdAt.toString())
                                put("licenses", row.licenses)
                                put("activeLicenses", row.activeLicenses)
                                put("lastActiveAt", row.lastActiveAt?.toString())
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handleUserDetail(id: String): HttpResponseContext {
        val uuid = UUID.fromString(id)
        val detail = queries.userDetail(uuid) ?: return plain(404, buildJsonObject { put("error", "not_found") })
        return plain(
            200, buildJsonObject {
                putJsonObject("account") {
                    put("id", detail.account.accountId.toString())
                    put("externalRef", detail.account.externalRef)
                    put("status", detail.account.status)
                    put("createdAt", detail.account.createdAt.toString())
                    put("lastActiveAt", detail.account.lastActiveAt?.toString())
                }
                putJsonArray("licenses") {
                    detail.licenses.forEach { add(licenseJson(it)) }
                }
                putJsonArray("devices") {
                    detail.devices.forEach { add(deviceJson(it)) }
                }
                putJsonObject("usage") {
                    put("requests", detail.usageSummary.requests)
                    put("errors", detail.usageSummary.errors)
                    put("inputTokens", detail.usageSummary.inputTokens)
                    put("outputTokens", detail.usageSummary.outputTokens)
                    put("promptChars", "NOT EXPOSED")
                }
            }
        )
    }

    private fun handleDevices(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        val rows = queries.devices(size, offset)
        return plain(
            200, buildJsonObject {
                putJsonArray("devices") { rows.forEach { add(deviceJson(it)) } }
            }
        )
    }

    private fun handleDevice(id: String): HttpResponseContext {
        val device = queries.device(UUID.fromString(id))
            ?: return plain(404, buildJsonObject { put("error", "not_found") })
        return plain(200, deviceJson(device))
    }

    private fun handleDeviceRevoke(principal: AdminPrincipal, request: HttpRequestContext, id: String): HttpResponseContext {
        val tokenId = UUID.fromString(id)
        val device = queries.device(tokenId)
            ?: return plain(404, buildJsonObject { put("error", "not_found") })
        val changed = queries.revokeDeviceToken(tokenId, java.time.Instant.now())
        audit.append(
            actor = principal.actor, action = "device.revoke", entityType = "API_TOKEN",
            entityId = tokenId.toString(), oldValue = """{"status":"${device.status}"}""",
            newValue = """{"status":"REVOKED"}""", remoteAddress = request.remoteAddress,
            sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("revoked", changed); put("status", "REVOKED") })
    }

    /* ── handlers: licenses / subscriptions ───────────────────────────────── */

    private fun handleLicenses(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        // ADMIN (MVP-дерево: Licenses → active/expired): фильтр по статусу.
        // Неизвестное значение — 400, а не тихое «показать всё»: тихий фолбэк
        // маскировал бы опечатку как полный список.
        val statusFilter = queryParam(request, "status")?.let { raw ->
            runCatching {
                com.jarvis.server.license.LicenseStatus.valueOf(raw.uppercase().trim())
            }.getOrElse { throw SettingsValidationError("unknown license status '$raw'") }
        }
        val rows = queries.licenses(size, offset, statusFilter)
        return plain(
            200, buildJsonObject {
                put("statusFilter", statusFilter?.name ?: "ALL")
                putJsonArray("licenses") { rows.forEach { add(licenseJson(it)) } }
            }
        )
    }

    private fun handleLicense(id: String): HttpResponseContext {
        val license = queries.license(UUID.fromString(id))
            ?: return plain(404, buildJsonObject { put("error", "not_found") })
        return plain(200, licenseJson(license))
    }

    private fun handleLicenseAction(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        id: String,
        action: String
    ): HttpResponseContext {
        val licenseId = UUID.fromString(id)
        val before = queries.license(licenseId)
            ?: return plain(404, buildJsonObject { put("error", "not_found") })
        val now = java.time.Instant.now()
        val changed: Boolean
        val newValue: String
        when (action) {
            "disable" -> {
                changed = queries.suspendLicense(licenseId, now)
                newValue = """{"status":"DISABLED"}"""
            }
            "enable" -> {
                changed = queries.resumeLicense(licenseId, now)
                newValue = """{"status":"ACTIVE"}"""
            }
            "extend" -> {
                val days = bodyJson(request)?.get("days")?.let { (it as? JsonPrimitive)?.content }?.toIntOrNull()
                    ?: throw SettingsValidationError("body must contain integer 'days'")
                if (days !in 1..3650) throw SettingsValidationError("days must be in 1..3650")
                changed = queries.extendLicense(licenseId, days, now)
                newValue = """{"extendedDays":$days}"""
            }
            "change-plan" -> {
                val planId = bodyJson(request)?.get("planId")?.let { (it as? JsonPrimitive)?.content }
                    ?: throw SettingsValidationError("body must contain 'planId'")
                changed = queries.changeLicensePlan(licenseId, planId, now)
                if (!changed) throw SettingsValidationError("plan '$planId' not found or license not mutable")
                newValue = """{"planId":"${planId.take(64)}"}"""
            }
            else -> return plain(404, buildJsonObject { put("error", "unknown_action") })
        }
        if (changed) {
            audit.append(
                actor = principal.actor, action = "license.$action", entityType = "LICENSE",
                entityId = licenseId.toString(),
                oldValue = """{"status":"${before.status}","plan":"${before.planId}"}""",
                newValue = newValue, remoteAddress = request.remoteAddress,
                sessionId = principal.sessionId, requestId = null
            )
        }
        return plain(200, buildJsonObject { put("changed", changed) })
    }

    private fun handleSubscriptions(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        val rows = queries.orders(size, offset)
        return plain(
            200, buildJsonObject {
                putJsonArray("orders") {
                    rows.forEach { order ->
                        add(
                            buildJsonObject {
                                put("id", order.id.toString())
                                put("accountId", order.accountId.toString())
                                put("planId", order.planId)
                                put("provider", order.provider)
                                put("status", order.status)
                                put("amountMinor", order.amountMinor)
                                put("currency", order.currency)
                                put("createdAt", order.createdAt.toString())
                                put("paidAt", order.paidAt?.toString())
                            }
                        )
                    }
                }
            }
        )
    }

    /* ── handlers: providers (секреты маскируются!) ───────────────────────── */

    private fun handleProviders(): HttpResponseContext {
        val snapshot = providerManager.healthSnapshot()
        val aiSettings = settings.ai()
        return plain(
            200, buildJsonObject {
                putJsonArray("providers") {
                    ProviderId.entries.forEach { id ->
                        val snapshotEntry = snapshot[id]
                        val override = aiSettings.providers[id.name]
                        add(
                            buildJsonObject {
                                put("id", id.name)
                                put("status", snapshotEntry?.status?.name ?: "UNKNOWN")
                                put("circuit", snapshotEntry?.circuitState?.name ?: "UNKNOWN")
                                put("enabledOverride", override?.enabled)
                                put("priorityOverride", override?.priority)
                                // API-ключи никогда не возвращаются: только факт конфигурации.
                                put("apiKey", "••••CONFIGURED")
                                put("requests", "SEE /v1/admin/usage")
                                put("latencyP50", "NOT MEASURED")
                                put("latencyP95", "NOT MEASURED")
                            }
                        )
                    }
                }
                putJsonObject("runtimeNote") {
                    put("priority", "applies immediately (Validate→Persist→Audit→Apply)")
                    put("enabled", "applies immediately")
                    put("timeout", "requiresRestart")
                    put("retry", "requiresRestart")
                }
            }
        )
    }

    private fun handleProviderConfigure(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        id: String
    ): HttpResponseContext {
        val providerId = runCatching { ProviderId.valueOf(id) }
            .getOrElse { return plain(404, buildJsonObject { put("error", "unknown_provider") }) }
        val body = bodyJson(request)
            ?: throw SettingsValidationError("body must be a JSON object")
        val currentAi = settings.ai()
        val currentOverride = currentAi.providers[id] ?: AiProviderOverride()
        val newEnabled = body["enabled"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
            ?: currentOverride.enabled
        val newPriority = body["priority"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?: currentOverride.priority
        val nextOverride = AiProviderOverride(enabled = newEnabled, priority = newPriority)
        val nextSettings = currentAi.copy(providers = currentAi.providers + (id to nextOverride))
        // Validate (init-блок) → Persist → Audit → Apply:
        val update = settings.updateAi(principal.actor, nextSettings)
        applyOverrides(update.newValue)
        audit.append(
            actor = principal.actor, action = "provider.configure", entityType = "PROVIDER",
            entityId = providerId.name,
            oldValue = """{"enabled":${currentOverride.enabled},"priority":${currentOverride.priority}}""",
            newValue = """{"enabled":$newEnabled,"priority":$newPriority}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("applied", true); put("version", update.newVersion) })
    }

    /** Apply: маппинг settings → runtime overrides (вызывается и при старте). */
    fun applyOverrides(ai: AiRoutingSettings) {
        overrides.apply(
            ai.providers.entries
                .mapNotNull { (name, override) ->
                    val id = runCatching { ProviderId.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                    val enabled = override.enabled ?: true
                    id to ProviderRuntimeOverrides.Override(enabled = enabled, priority = override.priority)
                }
                .toMap()
        )
    }

    /* ── handlers: usage / cost / logs / audit ────────────────────────────── */

    private fun periodDays(request: HttpRequestContext): Int =
        queryParam(request, "days")?.toIntOrNull()?.coerceIn(1, 90) ?: 7

    private fun handleUsage(request: HttpRequestContext): HttpResponseContext {
        val summary = queries.usageSummary(clientId = null, days = periodDays(request))
        return plain(
            200, buildJsonObject {
                put("periodDays", summary.periodDays)
                put("requests", summary.requests)
                put("cloudRequests", summary.requests)
                // Локальные выполнения на устройстве серверу не видны — честно:
                put("localRequests", "NOT COLLECTED")
                put("errors", summary.errors)
                put("inputTokens", summary.inputTokens)
                put("outputTokens", summary.outputTokens)
                putJsonArray("byProvider") {
                    summary.byProvider.forEach { p ->
                        add(
                            buildJsonObject {
                                put("provider", p.provider)
                                put("requests", p.requests)
                                put("errors", p.errors)
                                put("inputTokens", p.inputTokens)
                                put("outputTokens", p.outputTokens)
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handleUsageCost(request: HttpRequestContext, sub: String): HttpResponseContext {
        if (sub != "cost") return plain(404, buildJsonObject { put("error", "not_found") })
        val summary = queries.usageSummary(clientId = null, days = periodDays(request))
        val totals = CostModel.calculate(summary.byProvider, settings.cost())
        return plain(
            200, buildJsonObject {
                put("periodDays", summary.periodDays)
                put("totalUsd", totals.totalUsd)
                put("knownUsd", totals.knownUsd)
                putJsonArray("unknownProviders") { totals.unknownProviders.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("lines") {
                    totals.lines.forEach { line ->
                        add(
                            buildJsonObject {
                                put("provider", line.provider)
                                put("inputTokens", line.inputTokens)
                                put("outputTokens", line.outputTokens)
                                put("usdPerMillionInput", line.usdPerMillionInput)
                                put("usdPerMillionOutput", line.usdPerMillionOutput)
                                put("costUsd", line.costUsd)
                                put("formula", line.formula)
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handleLogs(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        val rows = queries.logs(queryParam(request, "component"), size, offset)
        return plain(
            200, buildJsonObject {
                putJsonArray("logs") {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("time", row.occurredAt.toString())
                                put("component", row.component)
                                put("type", row.type)
                                put("actor", row.actor)
                                put("result", row.result)
                                put("latencyMs", row.latencyMs)
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handleAudit(request: HttpRequestContext): HttpResponseContext {
        val (size, offset) = pagination(request)
        val events = audit.find(
            AdminAuditQuery(
                action = queryParam(request, "action"),
                actor = queryParam(request, "actor"),
                limit = size, offset = offset
            )
        )
        return plain(
            200, buildJsonObject {
                putJsonArray("events") {
                    events.forEach { e ->
                        add(
                            buildJsonObject {
                                put("time", e.occurredAt.toString())
                                put("actor", e.actor)
                                put("action", e.action)
                                put("entityType", e.entityType)
                                put("entityId", e.entityId)
                                put("oldValue", e.oldValue)
                                put("newValue", e.newValue)
                                put("remoteAddress", e.remoteAddress)
                            }
                        )
                    }
                }
            }
        )
    }

    /* ── handlers: settings / features ────────────────────────────────────── */

    private fun handleGetSettings(section: String): HttpResponseContext {
        val (value, version) = settingsSnapshot(section) ?: return plain(404, buildJsonObject { put("error", "unknown_section") })
        return plain(
            200, buildJsonObject {
                put("section", section)
                version?.let { put("version", it) }
                put("value", json.parseToJsonElement(value))
                if (section == "ai") {
                    put("note", "timeout/retry changes require restart; priority/enabled apply immediately")
                }
            }
        )
    }

    private fun handlePutSettings(principal: AdminPrincipal, request: HttpRequestContext, section: String): HttpResponseContext {
        val body = bodyJson(request) ?: throw SettingsValidationError("body must be a JSON object")
        val update = when (section) {
            "system" -> settings.updateSystem(principal.actor, decodeSettings(body, "system", SystemSettings.serializer()))
            "security" -> settings.updateSecurity(principal.actor, decodeSettings(body, "security", SecuritySettings.serializer()))
            "ai" -> {
                val decoded = decodeSettings(body, "ai", AiRoutingSettings.serializer())
                val update = settings.updateAi(principal.actor, decoded)
                applyOverrides(decoded)
                update
            }
            "limits" -> settings.updateLimits(principal.actor, decodeSettings(body, "limits", LimitsSettings.serializer()))
            "cost" -> settings.updateCost(principal.actor, decodeSettings(body, "cost", CostSettings.serializer()))
            else -> return plain(404, buildJsonObject { put("error", "unknown_section") })
        }
        audit.append(
            actor = principal.actor, action = "settings.update", entityType = "SETTINGS",
            entityId = section, oldValue = "{}", newValue = """{"version":${update.newVersion}}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("applied", true); put("version", update.newVersion) })
    }

    private fun <T> decodeSettings(body: JsonObject, section: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        runCatching { json.decodeFromString(serializer, body.toString()) }
            .getOrElse { throw SettingsValidationError("invalid $section settings: ${it.message?.take(200)}") }

    private fun settingsSnapshot(section: String): Pair<String, Long?>? = when (section) {
        "system" -> settings.system().let { json.encodeToString(SystemSettings.serializer(), it) to settings.versionOf("system") }
        "security" -> settings.security().let { json.encodeToString(SecuritySettings.serializer(), it) to settings.versionOf("security") }
        "ai" -> settings.ai().let { json.encodeToString(AiRoutingSettings.serializer(), it) to settings.versionOf("ai") }
        "limits" -> settings.limits().let { json.encodeToString(LimitsSettings.serializer(), it) to settings.versionOf("limits") }
        "cost" -> settings.cost().let { json.encodeToString(CostSettings.serializer(), it) to settings.versionOf("cost") }
        else -> null
    }

    private fun handleFlags(): HttpResponseContext {
        val all = flags.all()
        return plain(
            200, buildJsonObject {
                putJsonArray("flags") {
                    flags.knownKeys.forEach { key ->
                        val flag = all[key]
                        add(
                            buildJsonObject {
                                put("key", key)
                                put("enabled", flag?.enabled ?: false)
                                put("rolloutPercent", flag?.rolloutPercent ?: 0)
                                put("description", flag?.description ?: "")
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handlePutFlag(principal: AdminPrincipal, request: HttpRequestContext, key: String): HttpResponseContext {
        val body = bodyJson(request) ?: throw SettingsValidationError("body must be a JSON object")
        val enabled = body["enabled"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
            ?: throw SettingsValidationError("body must contain boolean 'enabled'")
        val rollout = body["rolloutPercent"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?: if (enabled) 100 else 0
        val before = flags.get(key)
        val after = flags.upsert(
            key = key, enabled = enabled, rolloutPercent = rollout,
            description = body["description"]?.let { (it as? JsonPrimitive)?.content } ?: before?.description ?: "",
            actor = principal.actor
        )
        audit.append(
            actor = principal.actor, action = "feature.update", entityType = "FEATURE_FLAG",
            entityId = key,
            oldValue = """{"enabled":${before?.enabled ?: false},"rollout":${before?.rolloutPercent ?: 0}}""",
            newValue = """{"enabled":${after.enabled},"rollout":${after.rolloutPercent}}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("applied", true); put("key", key) })
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private fun deviceJson(device: AdminQueries.DeviceRow) = buildJsonObject {
        put("tokenId", device.tokenId.toString())
        put("accountId", device.accountId.toString())
        put("status", device.status)
        put("issuedAt", device.issuedAt.toString())
        put("lastUsedAt", device.lastUsedAt?.toString())
        put("expiresAt", device.expiresAt?.toString())
        put("deviceBinding", device.deviceBinding)
        // Модель/прошивка/батарея не телеметрируются клиентом — честно:
        put("model", "NOT COLLECTED")
        put("firmware", "NOT COLLECTED")
        put("battery", "NOT COLLECTED")
    }

    private fun licenseJson(license: AdminQueries.LicenseRow) = buildJsonObject {
        put("id", license.id.toString())
        put("status", license.status)
        put("billingStatus", license.billingStatus)
        put("codeHint", license.codeHint)
        put("planId", license.planId)
        put("accountId", license.accountId?.toString())
        put("issuedAt", license.issuedAt.toString())
        put("startsAt", license.startsAt?.toString())
        put("expiresAt", license.expiresAt?.toString())
        put("redeemedAt", license.redeemedAt?.toString())
    }

    /* ── handlers: operator accounts (ADMINS_MANAGE, ТЗ §4/§5) ─────────────── */

    private fun handleAdmins(): HttpResponseContext {
        val rows = accounts.list(limit = 100, offset = 0)
        return plain(
            200, buildJsonObject {
                putJsonArray("admins") {
                    rows.forEach { a ->
                        add(
                            buildJsonObject {
                                put("id", a.id.toString())
                                put("username", a.username)
                                put("role", a.role.name)
                                put("status", a.status)
                                put("createdAt", a.createdAt.toString())
                            }
                        )
                    }
                }
            }
        )
    }

    private fun handleAdminCreate(principal: AdminPrincipal, request: HttpRequestContext): HttpResponseContext {
        val body = bodyJson(request) ?: throw SettingsValidationError("body must be a JSON object")
        val username = body["username"]?.let { (it as? JsonPrimitive)?.content }.orEmpty()
        val password = body["password"]?.let { (it as? JsonPrimitive)?.content }.orEmpty()
        val role = body["role"]?.let { (it as? JsonPrimitive)?.content } ?: "SUPPORT"
        if (!Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$").matches(username)) {
            throw SettingsValidationError("invalid username")
        }
        val roleParsed = runCatching { AdminRole.valueOf(role) }
            .getOrElse { throw SettingsValidationError("unknown role: $role") }
        val account = try {
            accounts.create(username, AdminPasswords.hash(password), roleParsed, java.time.Instant.now())
        } catch (e: IllegalArgumentException) {
            throw SettingsValidationError(e.message ?: "invalid password")
        } catch (e: org.postgresql.util.PSQLException) {
            return plain(409, buildJsonObject { put("error", "username_taken") })
        }
        sessions.revokeAllForAccount(account.id, java.time.Instant.now())
        audit.append(
            actor = principal.actor, action = "admin.create", entityType = "ADMIN_ACCOUNT",
            entityId = account.id.toString(), oldValue = "{}",
            newValue = """{"username":"$username","role":"$role"}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("id", account.id.toString()); put("role", account.role.name) })
    }

    private fun handleAdminSetStatus(principal: AdminPrincipal, request: HttpRequestContext, id: String): HttpResponseContext {
        val status = bodyJson(request)?.get("status")?.let { (it as? JsonPrimitive)?.content }
            ?: throw SettingsValidationError("body must contain 'status' (ACTIVE|DISABLED)")
        if (status !in setOf("ACTIVE", "DISABLED")) throw SettingsValidationError("status must be ACTIVE or DISABLED")
        val accountId = UUID.fromString(id)
        accounts.setStatus(accountId, status, java.time.Instant.now())
        if (status == "DISABLED") sessions.revokeAllForAccount(accountId, java.time.Instant.now())
        audit.append(
            actor = principal.actor, action = "admin.set-status", entityType = "ADMIN_ACCOUNT",
            entityId = accountId.toString(), oldValue = "{}", newValue = """{"status":"$status"}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("status", status) })
    }

    private fun handleAdminSetPassword(principal: AdminPrincipal, request: HttpRequestContext, id: String): HttpResponseContext {
        val password = bodyJson(request)?.get("password")?.let { (it as? JsonPrimitive)?.content }
            ?: throw SettingsValidationError("body must contain 'password'")
        val accountId = UUID.fromString(id)
        try {
            accounts.setPasswordHash(accountId, AdminPasswords.hash(password), java.time.Instant.now())
        } catch (e: IllegalArgumentException) {
            throw SettingsValidationError(e.message ?: "invalid password")
        }
        sessions.revokeAllForAccount(accountId, java.time.Instant.now())
        audit.append(
            actor = principal.actor, action = "admin.set-password", entityType = "ADMIN_ACCOUNT",
            entityId = accountId.toString(), oldValue = "{}", newValue = """{"rotated":true}""",
            remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
        )
        return plain(200, buildJsonObject { put("rotated", true) })
    }

    private fun plain(status: Int, body: JsonObject): HttpResponseContext =
        HttpResponseContext(status, body.toString(), mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"))
}
