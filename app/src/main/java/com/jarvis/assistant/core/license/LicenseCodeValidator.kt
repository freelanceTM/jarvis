package com.jarvis.assistant.core.license

import java.time.Duration

/** Pure activation orchestration; no local code-generation or master-code path. */
class LicenseCodeValidator {
    sealed interface CodeVerdict {
        data class BoxCodeValid(val license: ServerLicenseRecord) : CodeVerdict
        data object RateLimited : CodeVerdict
        data object ServiceUnavailable : CodeVerdict
        data object Invalid : CodeVerdict
    }

    suspend fun redeem(
        cleanCode: String,
        serverValidator: LicenseServerValidator,
        currentHardwareId: String
    ): CodeVerdict {
        if (cleanCode.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH) return CodeVerdict.Invalid
        return when (val result = serverValidator.redeem(cleanCode, currentHardwareId)) {
            is ServerRedemptionResult.Success -> {
                val license = result.license
                val duration = runCatching {
                    Duration.between(license.startsAt, license.expiresAt).toDays()
                }.getOrDefault(0)
                if (duration in 1..MAX_LICENSE_DAYS && license.accessToken != null) {
                    CodeVerdict.BoxCodeValid(license)
                } else {
                    CodeVerdict.Invalid
                }
            }
            ServerRedemptionResult.NotRedeemable -> CodeVerdict.Invalid
            ServerRedemptionResult.RateLimited -> CodeVerdict.RateLimited
            ServerRedemptionResult.ServiceUnavailable -> CodeVerdict.ServiceUnavailable
        }
    }

    private companion object {
        const val MIN_CODE_LENGTH = 8
        const val MAX_CODE_LENGTH = 64
        const val MAX_LICENSE_DAYS = 3_650L
    }
}
