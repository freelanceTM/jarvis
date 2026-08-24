package com.jarvis.server.config

import com.jarvis.server.billing.HeleketBillingConfig
import com.jarvis.server.billing.PaddleBillingConfig
import com.jarvis.server.license.BillingPlan
import com.jarvis.server.persistence.DatabaseConfig

data class LicenseSubsystemConfig(
    val database: DatabaseConfig,
    val codePepper: String,
    val plans: List<BillingPlan>,
    val redeemRateLimit: RateLimitConfig,
    val authenticatedRateLimit: RateLimitConfig,
    val webhookRateLimit: RateLimitConfig,
    val paddle: PaddleBillingConfig,
    val heleket: HeleketBillingConfig
) {
    init {
        require(codePepper.toByteArray().size >= 32) { "LICENSE_CODE_PEPPER must be at least 32 bytes" }
        require(plans.isNotEmpty()) { "At least one BILLING_PLANS entry is required" }
        require(plans.map { it.id }.distinct().size == plans.size) { "Duplicate billing plan ID" }
    }
}
