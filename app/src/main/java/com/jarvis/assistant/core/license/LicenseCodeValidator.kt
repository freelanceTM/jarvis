package com.jarvis.assistant.core.license

/**
 * Чистая оркестрация проверки кодов активации.
 *
 * Любой код — box, promotional или master — подтверждает только сервер.
 * Клиент не получает списки мастер-кодов и не содержит checksum/соль, поэтому
 * извлечение APK или перехват remote-config не даёт способа выпускать лицензии.
 */
class LicenseCodeValidator {

    sealed interface CodeVerdict {
        data class BoxCodeValid(val licenseDays: Int = DEFAULT_LICENSE_DAYS) : CodeVerdict
        data object ServiceUnavailable : CodeVerdict
        data object Invalid : CodeVerdict
    }

    suspend fun validate(
        cleanCode: String,
        serverValidator: LicenseServerValidator,
        currentHardwareId: String
    ): CodeVerdict {
        if (cleanCode.length < MIN_CODE_LENGTH) return CodeVerdict.Invalid

        return when (val result = serverValidator.validate(cleanCode, currentHardwareId)) {
            is ServerValidationResult.Valid -> {
                if (result.licenseDays in 1..MAX_LICENSE_DAYS) {
                    CodeVerdict.BoxCodeValid(result.licenseDays)
                } else {
                    CodeVerdict.Invalid
                }
            }
            is ServerValidationResult.Invalid -> CodeVerdict.Invalid
            ServerValidationResult.ServiceUnavailable -> CodeVerdict.ServiceUnavailable
        }
    }

    private companion object {
        const val MIN_CODE_LENGTH = 8
        const val DEFAULT_LICENSE_DAYS = 30
        const val MAX_LICENSE_DAYS = 3_650
    }
}
