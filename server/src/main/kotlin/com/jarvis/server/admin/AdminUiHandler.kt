package com.jarvis.server.admin

import com.jarvis.server.auth.Authenticator
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.HttpResponseContext
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * SERVER-RENDERED OPERATIONAL UI Control Plane (ТЗ §24–§26: dense, fast,
 * minimal; desktop-first). Без внешних CDN/шрифтов — страница полностью
 * self-contained (работает в закрытых сетях). Стек — существующий JVM-сервис:
 * деплой single-instance не меняется, Admin API остаётся UI-agnostic
 * (Next.js-клиент можно добавить позже поверх того же API).
 *
 * Аутентификация UI: HttpOnly SameSite=Strict cookie `admin_session`
 * (тот же [AdminAuthService]); мутации через формы требуют CSRF-токен
 * (детерминированный от сессии, double-submit). API-клиенты продолжают
 * использовать Authorization header.
 */
class AdminUiHandler(
    private val auth: AdminAuthService,
    private val staticAuthenticator: Authenticator?,
    private val audit: AdminAuditLog,
    private val settings: AdminSettingsService,
    private val flags: FeatureFlagService,
    private val queries: AdminQueries,
    private val providerManager: com.jarvis.server.provider.ProviderManager,
    private val json: Json = Json
) {

    fun handle(request: HttpRequestContext): HttpResponseContext {
        val path = request.path.substringBefore('?').removePrefix("/v1/admin/ui").trim('/')
        val cookie = cookieValue(request, "admin_session")
        val authResult = cookie?.let { auth.authenticate("Bearer $it", staticAuthenticator) }
        val principal = if (authResult is AdminAuthResult.Success) {
            authResult.principal
        } else {
            null
        }

        // Login/логаут доступны без principal.
        if (path == "login") {
            return if (request.method == "POST") {
                handleLoginPost(request)
            } else {
                html(200, loginPage())
            }
        }
        if (path == "logout" && request.method == "POST") {
            cookie?.let { auth.logout("Bearer $it") }
            return html(
                303, "",
                headers = mapOf(
                    "Location" to "/v1/admin/ui/login",
                    "Set-Cookie" to "admin_session=; HttpOnly; SameSite=Strict; Max-Age=0; Path=/v1/admin/ui"
                )
            )
        }

        if (principal == null) {
            return html(303, "", headers = mapOf("Location" to "/v1/admin/ui/login"))
        }

        if (request.method == "POST" && path.startsWith("licenses/")) {
            return licenseAction(principal, request, path.removePrefix("licenses/"))
        }
        return when {
            path.isEmpty() || path == "dashboard" -> html(200, dashboard(principal))
            path == "users" -> html(200, users(principal))
            path.startsWith("users/") -> html(200, userDetail(principal, path.removePrefix("users/")))
            path == "devices" -> html(200, devices(principal))
            path == "licenses" -> html(200, licenses(principal))
            path.startsWith("licenses/") -> html(200, licenseDetail(principal, path.removePrefix("licenses/"), csrfToken(request)))
            path == "providers" -> html(200, providers(principal))
            path == "usage" -> html(200, usage(principal))
            path == "logs" -> html(200, logs(principal))
            path == "audit" -> html(200, audit(principal))
            path == "settings" -> html(200, settings(principal))
            path == "flags" -> html(200, flagsPage(principal))
            else -> html(404, errorPage("404"))
        }
    }

    /* ── actions (POST, CSRF-защищённые) ──────────────────────────────────── */

    private fun handleLoginPost(request: HttpRequestContext): HttpResponseContext {
        val params = formParams(request)
        val username = params["username"].orEmpty()
        val password = params["password"].orEmpty()
        return when (val result = auth.login(username, password, request.remoteAddress)) {
            is AdminLoginResult.Success -> {
                audit.append(
                    actor = result.principal.actor, action = "admin.login.ui", entityType = "ADMIN_SESSION",
                    entityId = result.principal.sessionId?.toString(), oldValue = "{}", newValue = "{}",
                    remoteAddress = request.remoteAddress, sessionId = result.principal.sessionId, requestId = null
                )
                html(
                    303, "",
                    headers = mapOf(
                        "Location" to "/v1/admin/ui/dashboard",
                        "Set-Cookie" to cookie(result.rawToken)
                    )
                )
            }
            else -> html(200, loginPage(failed = true))
        }
    }

    private fun licenseAction(principal: AdminPrincipal, request: HttpRequestContext, id: String): HttpResponseContext {
        val expectedCsrf = cookieValue(request, "admin_session")?.let {
            AdminPasswords.sha256Hex("csrf|$it").take(32)
        }
        val csrf = formParams(request)["csrf"]
        if (csrf == null || expectedCsrf == null || csrf != expectedCsrf) return html(403, errorPage("CSRF"))
        if (!AdminRbac.can(principal.role, AdminPermission.LICENSES_WRITE)) {
            return html(403, errorPage("forbidden: LICENSES_WRITE"))
        }
        val licenseId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return html(404, errorPage("bad id"))
        val before = queries.license(licenseId) ?: return html(404, errorPage("not found"))
        val action = formParams(request)["action"].orEmpty()
        val ok = when (action) {
            "disable" -> queries.suspendLicense(licenseId, Instant.now())
            "enable" -> queries.resumeLicense(licenseId, Instant.now())
            else -> false
        }
        if (ok) {
            audit.append(
                actor = principal.actor, action = "license.$action", entityType = "LICENSE",
                entityId = licenseId.toString(), oldValue = """{"status":"${before.status}"}""",
                newValue = """{"via":"ui"}""", remoteAddress = request.remoteAddress,
                sessionId = principal.sessionId, requestId = null
            )
        }
        return html(303, "", headers = mapOf("Location" to "/v1/admin/ui/licenses/$id"))
    }

    /* ── pages ────────────────────────────────────────────────────────────── */

    private fun dashboard(principal: AdminPrincipal): String {
        val snap = queries.dashboard()
        val health = providerManager.healthSnapshot()
        val providers = health.entries.sortedBy { it.key.name }.joinToString("") { (id, s) ->
            row(id.name, s.status.name, s.circuitState.name)
        }
        return page(
            principal, "Dashboard",
            """
            <div class="grid">
              <div class="card"><h3>Users</h3><div class="big">${snap.accountsTotal}</div></div>
              <div class="card"><h3>Licenses active</h3><div class="big">${snap.licensesActive}</div><div class="sub">of ${snap.licensesIssued} issued</div></div>
              <div class="card"><h3>Device tokens</h3><div class="big">${snap.tokensActive}</div></div>
              <div class="card"><h3>Requests today</h3><div class="big">${snap.requestsToday}</div><div class="sub">errors: ${snap.errorsToday}</div></div>
              <div class="card"><h3>Tokens today</h3><div class="big">${snap.tokensToday}</div></div>
              <div class="card"><h3>Orders pending</h3><div class="big">${snap.ordersPending}</div></div>
            </div>
            <h3>Providers</h3>
            <table><tr><th>Provider</th><th>Status</th><th>Circuit</th></tr>$providers</table>
            <p class="sub">Local execution rate: NOT COLLECTED (executed on device; client telemetry pending)</p>
            """
        )
    }

    private fun users(principal: AdminPrincipal): String {
        val rows = queries.users(null, 50, 0)
        val body = rows.joinToString("") { r ->
            row(
                r.accountId.toString().take(8) + "…",
                r.externalRef ?: "-", r.status,
                r.licenses.toString() + " (" + r.activeLicenses + " active)",
                r.lastActiveAt?.toString()?.take(19) ?: "never",
                """<a href="/v1/admin/ui/users/${r.accountId}">open</a>"""
            )
        }
        return page(principal, "Users", """<table><tr><th>ID</th><th>Ref</th><th>Status</th><th>Licenses</th><th>Last active</th><th></th></tr>$body</table>""")
    }

    private fun userDetail(principal: AdminPrincipal, id: String): String {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return errorPage("bad id")
        val detail = queries.userDetail(uuid) ?: return errorPage("not found")
        val licenses = detail.licenses.joinToString("") { l ->
            row(l.id.toString().take(8) + "…", l.planId, l.status, l.billingStatus, l.expiresAt?.toString()?.take(10) ?: "-", "")
        }
        val devices = detail.devices.joinToString("") { d ->
            row(d.tokenId.toString().take(8) + "…", d.status, d.lastUsedAt?.toString()?.take(19) ?: "never", d.expiresAt?.toString()?.take(10) ?: "-", "")
        }
        return page(
            principal, "User ${detail.account.externalRef ?: detail.account.accountId}",
            """
            <p>Status: <b>${detail.account.status}</b>, created ${detail.account.createdAt.toString().take(10)}</p>
            <h3>Licenses</h3>
            <table><tr><th>ID</th><th>Plan</th><th>Status</th><th>Billing</th><th>Expires</th><th></th></tr>$licenses</table>
            <h3>Devices</h3>
            <table><tr><th>Token</th><th>Status</th><th>Last used</th><th>Expires</th></tr>$devices</table>
            <h3>Usage (30d, cloud)</h3>
            <p>requests: ${detail.usageSummary.requests}, errors: ${detail.usageSummary.errors}, in: ${detail.usageSummary.inputTokens}, out: ${detail.usageSummary.outputTokens}</p>
            <p class="sub">Conversation content is never exposed (privacy by design §28).</p>
            """
        )
    }

    private fun devices(principal: AdminPrincipal): String {
        val rows = queries.devices(50, 0)
        val body = rows.joinToString("") { d ->
            row(
                d.tokenId.toString().take(8) + "…",
                d.accountId.toString().take(8) + "…",
                d.status,
                d.lastUsedAt?.toString()?.take(19) ?: "never",
                d.deviceBinding ?: "-"
            )
        }
        return page(principal, "Devices", """<table><tr><th>Token</th><th>Owner</th><th>Status</th><th>Last used</th><th>Binding</th></tr>$body</table>""")
    }

    private fun licenses(principal: AdminPrincipal): String {
        val rows = queries.licenses(50, 0)
        val body = rows.joinToString("") { l ->
            row(
                """<a href="/v1/admin/ui/licenses/${l.id}">${l.id.toString().take(8)}…</a>""",
                l.planId, l.status, l.billingStatus, l.expiresAt?.toString()?.take(10) ?: "-"
            )
        }
        return page(principal, "Licenses", """<table><tr><th>ID</th><th>Plan</th><th>Status</th><th>Billing</th><th>Expires</th></tr>$body</table>""")
    }

    private fun licenseDetail(principal: AdminPrincipal, id: String, csrf: String?): String {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return errorPage("bad id")
        val license = queries.license(uuid) ?: return errorPage("not found")
        
        return page(
            principal, "License ${license.id.toString().take(8)}…",
            """
            <p>Plan: <b>${license.planId}</b>, status <b>${license.status}</b>, billing ${license.billingStatus},
            expires ${license.expiresAt?.toString()?.take(10) ?: "-"}</p>
            ${if (AdminRbac.can(principal.role, AdminPermission.LICENSES_WRITE)) {
                """
                <form method="post" action="/v1/admin/ui/licenses/$id">
                  <input type="hidden" name="csrf" value="$csrf">
                  <button name="action" value="disable">Suspend</button>
                  <button name="action" value="enable">Resume</button>
                </form>
                """
            } else {
                """<p class="sub">read-only (role ${principal.role})</p>"""
            }}
            """
        )
    }


    private fun providers(principal: AdminPrincipal): String {
        val health = providerManager.healthSnapshot()
        val ai = settings.ai()
        val rows = health.entries.sortedBy { it.key.name }.joinToString("") { (id, s) ->
            val o = ai.providers[id.name]
            row(
                id.name, s.status.name, s.circuitState.name,
                o?.enabled?.toString() ?: "startup-config",
                o?.priority?.toString() ?: "startup-config",
                s.permanentReason ?: "-"
            )
        }
        return page(
            principal, "AI Providers",
            """<table><tr><th>Provider</th><th>Status</th><th>Circuit</th><th>Enabled (override)</th><th>Priority (override)</th><th>Note</th></tr>$rows</table>
               <p class="sub">API keys are never exposed. Configure via API: POST /v1/admin/providers/{id}/configure. Timeout/retry require restart.</p>"""
        )
    }

    private fun usage(principal: AdminPrincipal): String {
        val summary = queries.usageSummary(clientId = null, days = 7)
        val totals = CostModel.calculate(summary.byProvider, settings.cost())
        val lines = totals.lines.joinToString("") { l ->
            row(l.provider, l.inputTokens?.toString() ?: "-", l.outputTokens?.toString() ?: "-", l.costUsd?.toString() ?: "UNKNOWN", l.formula)
        }
        return page(
            principal, "Usage & Cost (7d)",
            """
            <p>requests: <b>${summary.requests}</b>, errors: ${summary.errors}, in-tokens: ${summary.inputTokens}, out-tokens: ${summary.outputTokens}</p>
            <p>Total cost: <b>${totals.totalUsd?.toString() ?: "UNKNOWN"}</b> (known parts: $${totals.knownUsd})</p>
            <table><tr><th>Provider</th><th>In tokens</th><th>Out tokens</th><th>Cost USD</th><th>Formula</th></tr>$lines</table>
            <p class="sub">Local executions are not visible to the server (NOT COLLECTED). Prices configured in settings/cost; unset price = UNKNOWN, never guessed.</p>
            """
        )
    }

    private fun logs(principal: AdminPrincipal): String {
        val rows = queries.logs(null, 50, 0)
        val body = rows.joinToString("") { r ->
            row(
                r.occurredAt.toString().take(19), r.component, r.type.take(24), r.actor.take(28), r.result.take(24),
                r.latencyMs?.toString() ?: "-"
            )
        }
        return page(
            principal, "Operational Logs",
            """<table><tr><th>Time</th><th>Component</th><th>Type</th><th>Actor</th><th>Result</th><th>Latency ms</th></tr>$body</table>
               <p class="sub">Metadata only — private content is never logged (§18/§28).</p>"""
        )
    }

    private fun audit(principal: AdminPrincipal): String {
        val events = audit.find(AdminAuditQuery(limit = 50))
        val body = events.joinToString("") { e ->
            row(
                e.occurredAt.toString().take(19), e.actor.take(24), e.action, e.entityType,
                (e.entityId ?: "-").take(12), esc(e.newValue.take(40))
            )
        }
        return page(principal, "Audit Log", """<table><tr><th>Time</th><th>Actor</th><th>Action</th><th>Entity</th><th>ID</th><th>New</th></tr>$body</table>""")
    }

    private fun settings(principal: AdminPrincipal): String {
        fun sec(name: String, value: String) = row(name, "<code>${esc(value)}</code>")
        return page(
            principal, "Settings",
            """
            <table><tr><th>Section</th><th>Value (runtime)</th></tr>
            ${sec("system", json.encodeToString(SystemSettings.serializer(), settings.system()))}
            ${sec("security", json.encodeToString(SecuritySettings.serializer(), settings.security()))}
            ${sec("ai", json.encodeToString(AiRoutingSettings.serializer(), settings.ai()))}
            ${sec("limits", json.encodeToString(LimitsSettings.serializer(), settings.limits()))}
            ${sec("cost", json.encodeToString(CostSettings.serializer(), settings.cost()))}
            </table>
            <p class="sub">Mutations via API: PUT /v1/admin/settings/{section} (validate → persist → audit → apply). Timeout/retry require restart.</p>
            """
        )
    }

    private fun flagsPage(principal: AdminPrincipal): String {
        val all = flags.all()
        val body = flags.knownKeys.joinToString("") { key ->
            val f = all[key]
            row(key, (f?.enabled ?: false).toString(), (f?.rolloutPercent ?: 0).toString() + "%", f?.description ?: "")
        }
        return page(principal, "Feature Flags", """<table><tr><th>Key</th><th>Enabled</th><th>Rollout</th><th>Description</th></tr>$body</table>""")
    }

    /* ── scaffolding ──────────────────────────────────────────────────────── */

    /** CSRF: детерминированный от cookie-сессии токен (double-submit). */
    private fun csrfToken(request: HttpRequestContext): String? =
        cookieValue(request, "admin_session")?.let { AdminPasswords.sha256Hex("csrf|$it").take(32) }

    private fun loginPage(failed: Boolean = false): String {
        val msg = if (failed) """<p class="err">Неверные учётные данные или rate limit.</p>""" else ""
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>OMNIX Control Plane</title><style>$CSS</style></head><body>
        <div class="login"><h2>OMNIX Control Plane</h2>$msg
        <form method="post" action="/v1/admin/ui/login">
          <input name="username" placeholder="username" autocomplete="username" required><br>
          <input name="password" type="password" placeholder="password" autocomplete="current-password" required><br>
          <button>Sign in</button>
        </form></div></body></html>
        """.trimIndent()
    }

    private fun page(principal: AdminPrincipal, title: String, content: String): String {
        val nav = listOf(
            "dashboard" to "Dashboard", "users" to "Users", "devices" to "Devices",
            "licenses" to "Licenses", "providers" to "Providers", "usage" to "Usage/Cost",
            "logs" to "Logs", "audit" to "Audit", "settings" to "Settings", "flags" to "Flags"
        ).joinToString("") { (path, label) ->
            """<a href="/v1/admin/ui/$path">$label</a>"""
        }
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title · OMNIX</title><style>$CSS</style></head><body>
        <header><span class="brand">OMNIX CONTROL PLANE</span> $nav
        <form method="post" action="/v1/admin/ui/logout" class="inline"><button class="ghost">Logout (${esc(principal.actor)})</button></form>
        </header>
        <main><h2>$title</h2>$content</main>
        </body></html>
        """.trimIndent()
    }

    private fun errorPage(message: String): String =
        """<!doctype html><html><head><meta charset="utf-8"><title>OMNIX</title><style>$CSS</style></head>
           <body><main><h2>Ошибка</h2><p>${esc(message)}</p><p><a href="/v1/admin/ui/dashboard">← dashboard</a></p></main></body></html>"""

    private fun row(vararg cells: String): String =
        "<tr>" + cells.joinToString("") { "<td>${it}</td>" } + "</tr>"

    private fun cookie(token: String): String =
        "admin_session=$token; HttpOnly; SameSite=Strict; Path=/v1/admin/ui; Max-Age=${Duration.ofMinutes(60).toSeconds()}"

    private fun cookieValue(request: HttpRequestContext, name: String): String? =
        request.header("Cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun formParams(request: HttpRequestContext): Map<String, String> =
        request.body.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to urlDecode(pair.substring(idx + 1))
        }.toMap()

    private fun urlDecode(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8)

    private fun esc(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun html(status: Int, body: String, headers: Map<String, String> = emptyMap()): HttpResponseContext =
        HttpResponseContext(
            status, body,
            headers + mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store", "X-Frame-Options" to "DENY")
        )

    private companion object {
        // Dense operational theme: dark, small paddings, no decorative chrome.
        const val CSS = """
        :root{--bg:#0d1117;--panel:#161b22;--fg:#c9d1d9;--mut:#8b949e;--acc:#58a6ff;--ok:#3fb950;--warn:#d29922;--err:#f85149}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
        header{display:flex;align-items:center;gap:14px;padding:10px 16px;background:var(--panel);flex-wrap:wrap}
        .brand{font-weight:700;color:var(--acc);letter-spacing:.08em}
        nav,a{color:var(--acc);text-decoration:none}header a{margin-right:10px}
        .inline{display:inline;margin-left:auto}
        main{padding:16px;max-width:1200px;margin:0 auto}h2{margin-top:6px}h3{color:var(--mut)}
        table{border-collapse:collapse;width:100%;margin:8px 0}
        th,td{border:1px solid #21262d;padding:6px 10px;text-align:left;overflow-wrap:anywhere}
        th{background:var(--panel);color:var(--mut);font-weight:600}
        tr:hover td{background:#10151d}
        .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}
        .card{background:var(--panel);border:1px solid #21262d;padding:12px;border-radius:6px}
        .big{font-size:26px;font-weight:700}.sub{color:var(--mut);font-size:12px}
        button{background:var(--acc);border:0;color:#0d1117;padding:7px 14px;border-radius:4px;cursor:pointer;font-weight:600}
        .ghost{background:transparent;color:var(--mut);border:1px solid #30363d}
        input{background:#0d1117;border:1px solid #30363d;color:var(--fg);padding:8px;margin:4px 0;border-radius:4px;width:240px}
        .err{color:var(--err)}.login{max-width:340px;margin:12vh auto;background:var(--panel);padding:24px;border-radius:8px;border:1px solid #21262d}
        @media(max-width:700px){table{font-size:12px}.grid{grid-template-columns:repeat(2,1fr)}}
        """
    }
}
