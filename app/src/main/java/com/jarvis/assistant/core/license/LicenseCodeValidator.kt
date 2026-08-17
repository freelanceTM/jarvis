package com.jarvis.assistant.core.license

/**
 * Чистая логика валидации кодов активации (без Android — unit-тестируема).
 *
 * Два независимых пути:
 *  1. Обычные скретч-коды коробки — финальная проверка на СЕРВЕРЕ
 *     ([LicenseServerValidator]). Клиентский алгоритм контрольной суммы
 *     остался только как ВРЕМЕННЫЙ офлайн-fallback ([LocalChecksumVerifier],
 *     TODO(server): удалить) — см. пункт аудита #2.
 *  2. Мастер-коды — ТОЛЬКО из удалённого конфига [LicenseRemoteConfig].
 *
 * Одноразовость мастер-кода и привязка к hardware ID проверяются здесь же
 * (по данным, которые передаёт вызывающий слой), а финальное cross-device
 * отслеживание — TODO(server).
 */
class LicenseCodeValidator {

    /** Результат проверки кода. */
    sealed interface CodeVerdict {
        /** Обычный скретч-код коробки — валиден (сервер подтвердил или временный fallback). */
        data object BoxCodeValid : CodeVerdict

        /** Мастер-код из удалённого конфига — валиден и ещё не использован. */
        data object MasterCodeValid : CodeVerdict

        /** Мастер-код уже был использован на этом устройстве. */
        data object MasterCodeAlreadyUsed : CodeVerdict

        /** Код не прошёл проверку (сервер отклонил / не в списке / fallback не прошёл). */
        data object Invalid : CodeVerdict
    }

    /**
     * @param cleanCode       код, приведённый к каноническому виду (верхний регистр, без пробелов/дефисов)
     * @param remoteConfig    удалённый конфиг лицензий; null — источник недоступен
     * @param serverValidator серверная валидация box-кодов (источник правды)
     * @param currentHardwareId hardware ID текущего устройства
     * @param usedMasterCodes коды, уже использованные на этом устройстве
     * @param codeBoundToHardwareId hardware ID, к которому код уже привязан (если есть)
     */
    suspend fun validate(
        cleanCode: String,
        remoteConfig: LicenseConfigData?,
        serverValidator: LicenseServerValidator,
        currentHardwareId: String,
        usedMasterCodes: Set<String>,
        codeBoundToHardwareId: String?
    ): CodeVerdict {
        if (cleanCode.length < MIN_CODE_LENGTH) return CodeVerdict.Invalid

        // 1. Мастер-коды — только через удалённый конфиг. Без сервера (null)
        //    или при выключенном рубильнике мастер-коды НЕ работают.
        if (remoteConfig?.masterCodesEnabled == true && cleanCode in remoteConfig.masterCodes) {
            return when {
                cleanCode in usedMasterCodes -> CodeVerdict.MasterCodeAlreadyUsed
                codeBoundToHardwareId != null && codeBoundToHardwareId != currentHardwareId ->
                    CodeVerdict.MasterCodeAlreadyUsed // привязан к другому устройству
                else -> CodeVerdict.MasterCodeValid
            }
        }

        // 2. Box-коды: сервер — источник правды (пункт аудита #2).
        return when (serverValidator.validate(cleanCode, currentHardwareId)) {
            is ServerValidationResult.Valid -> CodeVerdict.BoxCodeValid
            is ServerValidationResult.Invalid -> CodeVerdict.Invalid
            ServerValidationResult.ServiceUnavailable -> {
                // ВРЕМЕННЫЙ офлайн-fallback (TODO(server): удалить вместе с
                // LocalChecksumVerifier, когда сервер будет развёрнут).
                if (LocalChecksumVerifier.passes(cleanCode)) {
                    CodeVerdict.BoxCodeValid
                } else {
                    CodeVerdict.Invalid
                }
            }
        }
    }

    private companion object {
        const val MIN_CODE_LENGTH = 8
    }
}
