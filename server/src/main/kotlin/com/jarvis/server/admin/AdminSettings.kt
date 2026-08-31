package com.jarvis.server.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/* ── Секция SYSTEM ───────────────────────────────────────────────────────── */

@Serializable
data class SystemSettings(
    val maintenanceMode: Boolean = false,
    val registrationOpen: Boolean = true,
    val defaultPlanId: String = "pro_monthly"
)

/* ── Секция SECURITY ─────────────────────────────────────────────────────── */

@Serializable
data class SecuritySettings(
    val sessionTtlMinutes: Int = 30,
    val loginMaxAttempts: Int = 5,
    val loginWindowMinutes: Int = 15,
    val minPasswordLength: Int = AdminPasswords.MIN_PASSWORD_LENGTH
) {
    init {
        require(sessionTtlMinutes in 5..720) { "sessionTtlMinutes must be in 5..720" }
        require(loginMaxAttempts in 1..100) { "loginMaxAttempts must be in 1..100" }
        require(loginWindowMinutes in 1..1440) { "loginWindowMinutes must be in 1..1440" }
        require(minPasswordLength >= AdminPasswords.MIN_PASSWORD_LENGTH) {
            "minPasswordLength cannot be lower than built-in floor"
        }
    }
}

/* ── Секция AI (routing policy — применяется в рантайме, ТЗ §11/§12) ─────── */

@Serializable
data class AiProviderOverride(
    val enabled: Boolean? = null,
    /** Меньше = выше приоритет отбора (как в ServerConfig.ProviderConfig). */
    val priority: Int? = null
)

@Serializable
data class AiRoutingSettings(
    val localFirstEnabled: Boolean = true,
    val cloudEscalationEnabled: Boolean = true,
    /**
     * Overrides поверх startup-конфигурации провайдеров. Timeout/retry здесь
     * НАМЕРЕННО нет: они зашиты в startup-конфиг и их смена в рантайме
     * несовместима с клиентскими дедлайнами — помечено requiresRestart в API.
     */
    val providers: Map<String, AiProviderOverride> = emptyMap()
) {
    init {
        providers.keys.forEach { key ->
            require(key in setOf("GROQ", "GEMINI", "OPENROUTER")) {
                "unknown provider id: $key"
            }
        }
        providers.values.forEach { override ->
            require(override.priority == null || override.priority in 1..1000) {
                "priority must be in 1..1000"
            }
        }
    }
}

/* ── Секция LIMITS ───────────────────────────────────────────────────────── */

@Serializable
data class LimitsSettings(
    val perDayRequests: Int = 2000,
    val perDayTokens: Long = 1_000_000,
    val perDayCostUsd: Double = 5.0
) {
    init {
        require(perDayRequests >= 0) { "perDayRequests must be non-negative" }
        require(perDayTokens >= 0) { "perDayTokens must be non-negative" }
        require(perDayCostUsd >= 0) { "perDayCostUsd must be non-negative" }
    }
}

/* ── Секция COST (server-side cost model, ТЗ §16) ────────────────────────── */

@Serializable
data class ProviderCostEntry(
    /** USD за 1M input-токенов. null = NOT CONFIGURED (никогда не выдумываем). */
    val usdPerMillionInput: Double? = null,
    /** USD за 1M output-токенов. */
    val usdPerMillionOutput: Double? = null
)

@Serializable
data class CostSettings(
    val providers: Map<String, ProviderCostEntry> = emptyMap()
) {
    init {
        providers.forEach { (id, entry) ->
            require(id in setOf("GROQ", "GEMINI", "OPENROUTER")) { "unknown provider id: $id" }
            require(entry.usdPerMillionInput == null || entry.usdPerMillionInput >= 0) {
                "usdPerMillionInput must be non-negative"
            }
            require(entry.usdPerMillionOutput == null || entry.usdPerMillionOutput >= 0) {
                "usdPerMillionOutput must be non-negative"
            }
        }
    }
}

/** Изменённая секция настроек — то, что уходит в audit. */
data class SettingsUpdate<T>(
    val key: String,
    val oldValue: T,
    val newValue: T,
    val newVersion: Long
)

/**
 * Централизованное хранилище настроек (ТЗ §20): validate → persist → audit → apply.
 * Кэш в памяти обновляется атомарно; каждое значение версионируется (optimistic).
 */
class AdminSettingsService(private val dataSource: DataSource, private val json: Json = Json) {

    private val systemCache = AtomicReference(SystemSettings())
    private val securityCache = AtomicReference(SecuritySettings())
    private val aiCache = AtomicReference(AiRoutingSettings())
    private val limitsCache = AtomicReference(LimitsSettings())
    private val costCache = AtomicReference(CostSettings())

    init {
        loadFromDatabase()
    }

    fun system(): SystemSettings = systemCache.get()
    fun security(): SecuritySettings = securityCache.get()
    fun ai(): AiRoutingSettings = aiCache.get()
    fun limits(): LimitsSettings = limitsCache.get()
    fun cost(): CostSettings = costCache.get()

    fun updateSystem(actor: String, next: SystemSettings): SettingsUpdate<SystemSettings> =
        update("system", SystemSettings.serializer(), next, systemCache, actor)

    fun updateSecurity(actor: String, next: SecuritySettings): SettingsUpdate<SecuritySettings> =
        update("security", SecuritySettings.serializer(), next, securityCache, actor)

    fun updateAi(actor: String, next: AiRoutingSettings): SettingsUpdate<AiRoutingSettings> =
        update("ai", AiRoutingSettings.serializer(), next, aiCache, actor)

    fun updateLimits(actor: String, next: LimitsSettings): SettingsUpdate<LimitsSettings> =
        update("limits", LimitsSettings.serializer(), next, limitsCache, actor)

    fun updateCost(actor: String, next: CostSettings): SettingsUpdate<CostSettings> =
        update("cost", CostSettings.serializer(), next, costCache, actor)

    /* Валидация отделена от записи: require-исключения (init-блоки data-классов)
       ловятся handler'ом и превращаются в 400; в БД попадает только валидное. */

    private fun <T> update(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        next: T,
        cache: AtomicReference<T>,
        actor: String
    ): SettingsUpdate<T> {
        val previous = cache.get()
        val raw = json.encodeToString(serializer, next)
        val newVersion = dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO admin_settings (key, value, version, updated_by, updated_at) VALUES (?, ?::jsonb, 1, ?, ?) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, " +
                    "version = admin_settings.version + 1, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at " +
                    "RETURNING version"
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, raw)
                ps.setString(3, actor.take(128))
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        cache.set(next)
        return SettingsUpdate(key, previous, next, newVersion)
    }

    private fun loadFromDatabase() {
        val loaders: Map<String, (String) -> Unit> = mapOf(
            "system" to { raw -> systemCache.set(json.decodeFromString(SystemSettings.serializer(), raw)) },
            "security" to { raw -> securityCache.set(json.decodeFromString(SecuritySettings.serializer(), raw)) },
            "ai" to { raw -> aiCache.set(json.decodeFromString(AiRoutingSettings.serializer(), raw)) },
            "limits" to { raw -> limitsCache.set(json.decodeFromString(LimitsSettings.serializer(), raw)) },
            "cost" to { raw -> costCache.set(json.decodeFromString(CostSettings.serializer(), raw)) }
        )
        loaders.forEach { (key, apply) ->
            // Повреждённая строка в БД не роняет старт: откат к дефолту.
            runCatching { readRaw(key) }.getOrNull()?.let { raw -> runCatching { apply(raw) } }
        }
    }

    private fun readRaw(key: String): String? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT value::text FROM admin_settings WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private fun serializerFor(key: String): kotlinx.serialization.KSerializer<out kotlinx.serialization.Serializable>? =
        when (key) {
            "system" -> SystemSettings.serializer()
            "security" -> SecuritySettings.serializer()
            "ai" -> AiRoutingSettings.serializer()
            "limits" -> LimitsSettings.serializer()
            "cost" -> CostSettings.serializer()
            else -> null
        }
}

    /** Версия сохранённой секции (null = ещё не менялась). */
    fun versionOf(key: String): Long? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT version FROM admin_settings WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }
