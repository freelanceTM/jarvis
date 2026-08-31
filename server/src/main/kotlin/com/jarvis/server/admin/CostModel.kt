package com.jarvis.server.admin

import kotlinx.serialization.Serializable

/**
 * SERVER-SIDE cost model (Control Plane ТЗ §16).
 *
 * Цены хранятся в admin_settings (секция `cost`) как USD за 1М токенов —
 * рыночный формат прайс-листов провайдеров. Расчёт детерминирован и
 * возвращаем «input / formula / result» для каждой строки.
 *
 * Неизвестная цена ≠ ноль: строка помечается UNKNOWN, никогда не выдумывается.
 */
object CostModel {

    @Serializable
    data class CostLine(
        val provider: String,
        /** Токены, ушедшие в расчёт (null = в записи не было токенов). */
        val inputTokens: Long?,
        val outputTokens: Long?,
        val usdPerMillionInput: Double?,
        val usdPerMillionOutput: Double?,
        /** null = UNKNOWN (цена не сконфигурирована или токены не учитывались). */
        val costUsd: Double?,
        /** Как считалось (для прозрачности UI). */
        val formula: String
    )

    @Serializable
    data class CostTotals(
        /** null = хотя бы одна строка UNKNOWN и точная сумма невозможна. */
        val totalUsd: Double?,
        val knownUsd: Double,
        val unknownProviders: List<String>,
        val lines: List<CostLine>
    )

    fun calculate(
        usage: List<ProviderTokenUsage>,
        prices: CostSettings
    ): CostTotals {
        val lines = usage.map { row ->
            val entry = prices.providers[row.provider]
            val priceIn = entry?.usdPerMillionInput
            val priceOut = entry?.usdPerMillionOutput
            val cost: Double? = if (priceIn != null && priceOut != null &&
                row.inputTokens != null && row.outputTokens != null
            ) {
                row.inputTokens / 1_000_000.0 * priceIn + row.outputTokens / 1_000_000.0 * priceOut
            } else {
                null
            }
            CostLine(
                provider = row.provider,
                inputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                usdPerMillionInput = priceIn,
                usdPerMillionOutput = priceOut,
                costUsd = cost?.let { round4(it) },
                formula = if (cost != null) {
                    "in/1e6*$priceIn + out/1e6*$priceOut"
                } else {
                    "UNKNOWN (missing ${missingParts(priceIn, priceOut, row)})"
                }
            )
        }
        val unknown = lines.filter { it.costUsd == null }.map { it.provider }
        val known = lines.mapNotNull { it.costUsd }.sum()
        return CostTotals(
            totalUsd = if (unknown.isEmpty()) round4(known) else null,
            knownUsd = round4(known),
            unknownProviders = unknown.distinct(),
            lines = lines
        )
    }

    private fun missingParts(
        priceIn: Double?,
        priceOut: Double?,
        row: ProviderTokenUsage
    ): String = buildList {
        if (priceIn == null) add("input price")
        if (priceOut == null) add("output price")
        if (row.inputTokens == null || row.outputTokens == null) add("token counts")
    }.joinToString(", ")

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}

/** Агрегированное потребление одного провайдера за период. */
data class ProviderTokenUsage(
    val provider: String,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long?,
    val outputTokens: Long?
)
