package com.jarvis.server.provider

import com.jarvis.server.admin.ProviderRuntimeOverrides
import com.jarvis.server.config.ProviderConfig

/**
 * Smart Provider Router (пункт спецификации: «хранить latency / errors / 429 /
 * availability / cost и выбирать best provider, а не random»).
 *
 * Схема уже была: AI Gateway ([com.jarvis.server.http.JarvisApiHandler]) →
 * Provider Router ([ProviderManager]) → [Groq | Gemini | OpenRouter].
 * Отбор ДО этого коммита — статический приоритет из конфига; теперь порядок
 * кандидатов определяется измеренными показателями
 * [ProviderPerformanceTracker] + конфигурируемыми ценами, со статическим
 * приоритетом как tie-break и cold-start fallback.
 *
 * Score (детерминированный, без случайности):
 *
 * ```
 * score = 0.35 · latency + 0.35 · reliability + 0.15 · (1 − 429share) + 0.15 · cost
 * ```
 *
 *  - latency — min-max нормализация EMA среди кандидатов (быстрейший = 1);
 *  - reliability — success rate провайдера;
 *  - 429 — доля RATE_LIMITED вычитается;
 *  - cost — min-max нормализация свёртки цен (USD/1K input+output) из
 *    admin settings; неизвестная цена = нейтраль (никогда не выдумывается,
 *    AR-02);
 *  - компонент без достаточных данных ([ProviderPerformanceTracker.MIN_SAMPLES]
 *    или один кандидат с данными) = нейтраль 0.5.
 *
 * Cold start: пока НИ У одного кандидата нет [ProviderPerformanceTracker.MIN_SAMPLES]
 * измерений — порядок ровно прежний (статический приоритет), поведение
 * сервера не меняется. Приоритет из конфига/overrides остаётся tie-break'ом,
 * а admin runtime-overrides по-прежнему перекрывают всё флагом enabled.
 */
class SmartProviderSelectionPolicy(
    private val configs: Map<ProviderId, ProviderConfig>,
    private val health: ProviderHealthTracker,
    private val overrides: ProviderRuntimeOverrides = ProviderRuntimeOverrides(),
    private val performance: ProviderPerformanceTracker = ProviderPerformanceTracker(),
    /** Источник цен (USD/1K) — читается на каждый отбор, обновляемый в рантайме. */
    private val prices: () -> Map<ProviderId, CostEstimate> = { emptyMap() }
) : ProviderSelectionPolicy {

    override fun select(
        providers: List<AiProvider>,
        requirements: ProviderRequirements
    ): List<AiProvider> {
        val candidates = providers.asSequence()
            .filter { provider ->
                val cfg = configs[provider.id]
                val runtimeEnabled = overrides.enabled(provider.id)
                cfg != null && cfg.enabled && (runtimeEnabled ?: true) && provider.isConfigured()
            }
            .filter { provider ->
                val caps = provider.capabilities
                (!requirements.requiresWeb || caps.supportsWeb) &&
                    (!requirements.requiresToolCalling || caps.supportsToolCalling)
            }
            .filter { health.isAvailable(it.id) }
            .toList()

        if (candidates.size <= 1) return candidates

        val staticPriority = { provider: AiProvider ->
            overrides.priority(provider.id) ?: configs[provider.id]?.priority ?: Int.MAX_VALUE
        }

        val perf = candidates.associate { it.id to performance.snapshot(it.id) }
        val warmCandidates = perf.values.count { it != null && it.samples >= ProviderPerformanceTracker.MIN_SAMPLES }
        if (warmCandidates == 0) {
            // Cold start: измерений ещё нет — прежняя семантика (статический
            // приоритет среди здоровых).
            return candidates.sortedBy(staticPriority)
        }

        val priceMap = prices()
        val weights = weightsFor(requirements.costClass)
        return candidates.sortedWith(
            compareByDescending<AiProvider> { score(it.id, perf, priceMap, weights) }
                .thenBy(staticPriority)
        )
    }

    /**
     * Budget policy (Cost Control): веса score по классу запроса.
     *  - SIMPLE — цена доминирует (0.5): короткая реплика должна уходить
     *    самому дешёвому; качество коротких ответов у всех кандидатов
     *    достаточное, переплачивать не за что;
     *  - MEDIUM — прежний баланс (поведение по умолчанию не меняется);
     *  - HARD — качество доминирует (reliability 0.5, цена 0): research
     *    и большой контекст не экономят на модели.
     */
    private fun weightsFor(costClass: com.jarvis.server.cost.CostClass): Weights = when (costClass) {
        com.jarvis.server.cost.CostClass.SIMPLE -> Weights(
            latency = 0.20, reliability = 0.20, rateLimit = 0.10, cost = 0.50
        )
        com.jarvis.server.cost.CostClass.MEDIUM -> Weights(
            latency = W_LATENCY, reliability = W_RELIABILITY,
            rateLimit = W_RATE_LIMIT, cost = W_COST
        )
        com.jarvis.server.cost.CostClass.HARD -> Weights(
            latency = 0.35, reliability = 0.50, rateLimit = 0.15, cost = 0.0
        )
    }

    private data class Weights(
        val latency: Double,
        val reliability: Double,
        val rateLimit: Double,
        val cost: Double
    )

    private fun score(
        id: ProviderId,
        perf: Map<ProviderId, ProviderPerformanceTracker.PerformanceSnapshot?>,
        priceMap: Map<ProviderId, CostEstimate>,
        weights: Weights
    ): Double {
        val snapshot = perf[id]
        val warm = snapshot != null && snapshot.samples >= ProviderPerformanceTracker.MIN_SAMPLES
        val latency = latencyScore(id, perf.values.filterNotNull())
        val reliability = if (warm) snapshot!!.successRate else NEUTRAL
        val rateLimit = if (warm) 1.0 - snapshot!!.rateLimitedShare else NEUTRAL
        val cost = costScore(id, priceMap)
        return weights.latency * latency + weights.reliability * reliability +
            weights.rateLimit * rateLimit + weights.cost * cost
    }

    /** Быстрейший измеренный кандидат = 1; нейтраль при недостатке данных. */
    private fun latencyScore(
        id: ProviderId,
        snapshots: List<ProviderPerformanceTracker.PerformanceSnapshot>
    ): Double {
        val measured = snapshots.mapNotNull { it.avgLatencyMs }
        val own = snapshots.firstOrNull { it.providerId == id }?.avgLatencyMs
            ?: return NEUTRAL
        if (measured.size < 2) return NEUTRAL
        val min = measured.min()
        val max = measured.max()
        return if (max <= min) 1.0 else (max - own) / (max - min)
    }

    /** Дешевейший кандидат (по свёртке цен) = 1; неизвестная цена = нейтраль. */
    private fun costScore(id: ProviderId, priceMap: Map<ProviderId, CostEstimate>): Double {
        val blended = priceMap.mapValues { (_, estimate) ->
            val input = estimate.usdPer1kInput
            val output = estimate.usdPer1kOutput
            if (input == null || output == null) null else input + output
        }
        val own = blended[id] ?: return NEUTRAL
        val known = blended.values.filterNotNull()
        if (known.size < 2) return NEUTRAL
        val min = known.min()
        val max = known.max()
        return if (max <= min) 1.0 else (max - own) / (max - min)
    }

    companion object {
        /** Вес латентности в score. */
        const val W_LATENCY = 0.35

        /** Вес надёжности (success rate). */
        const val W_RELIABILITY = 0.35

        /** Вес штрафа за 429. */
        const val W_RATE_LIMIT = 0.15

        /** Вес цены. */
        const val W_COST = 0.15

        /** Нейтральная оценка компонента без достаточных данных. */
        const val NEUTRAL = 0.5

        /**
         * Маппинг admin-цен (USD за 1М токенов) в [CostEstimate] (USD за 1K) —
         * единая точка конвертации форматов.
         */
        fun costEstimates(
            settings: com.jarvis.server.admin.CostSettings
        ): Map<ProviderId, CostEstimate> = settings.providers.mapNotNull { (key, entry) ->
            val id = runCatching { ProviderId.valueOf(key) }.getOrNull()
                ?: return@mapNotNull null
            id to CostEstimate(
                usdPer1kInput = entry.usdPerMillionInput?.div(1_000.0),
                usdPer1kOutput = entry.usdPerMillionOutput?.div(1_000.0)
            )
        }.toMap()
    }
}

/**
 * Обновляемый источник цен: в момент сборки ProviderManager БД/admin settings
 * ещё не существуют (Manager собирается раньше лицензионной подсистемы), поэтому
 * Main регистрирует реальный источник после их создания; политика читает
 * цены на КАЖДЫЙ отбор — обновления в admin подхватываются без рестарта.
 */
class CostPriceSource {
    @Volatile
    private var source: () -> com.jarvis.server.admin.CostSettings = {
        com.jarvis.server.admin.CostSettings()
    }

    fun set(source: () -> com.jarvis.server.admin.CostSettings) {
        this.source = source
    }

    fun get(): com.jarvis.server.admin.CostSettings = source()
}
