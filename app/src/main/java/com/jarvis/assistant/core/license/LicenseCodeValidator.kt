package com.jarvis.assistant.core.license

/**
 * Чистая логика валидации кодов активации (без Android — unit-тестируема).
 *
 * Два независимых пути:
 *  1. Обычные скретч-коды коробки — проверка контрольной суммы
 *     (TODO(server): алгоритм проверки перенести на сервер, чтобы соль и
 *     формула не извлекались реверс-инжинирингом из APK).
 *  2. Мастер-коды — ТОЛЬКО из удалённого конфига [LicenseRemoteConfig].
 *     Hardcoded мастер-коды удалены: без сервера они не работают.
 *
 * Одноразовость мастер-кода и привязка к hardware ID проверяются здесь же
 * (по данным, которые передаёт вызывающий слой), а финальное cross-device
 * отслеживание — TODO(server).
 */
class LicenseCodeValidator {

    /** Результат проверки кода. */
    sealed interface CodeVerdict {
        /** Обычный скретч-код коробки — валиден по контрольной сумме. */
        data object BoxCodeValid : CodeVerdict

        /** Мастер-код из удалённого конфига — валиден и ещё не использован. */
        data object MasterCodeValid : CodeVerdict

        /** Мастер-код уже был использован на этом устройстве. */
        data object MasterCodeAlreadyUsed : CodeVerdict

        /** Код не прошёл проверку (неверная сумма / не в списке / конфиг недоступен). */
        data object Invalid : CodeVerdict
    }

    /**
     * @param cleanCode     код, приведённый к каноническому виду (верхний регистр, без пробелов/дефисов)
     * @param remoteConfig  удалённый конфиг лицензий; null — источник недоступен
     * @param usedMasterCodes коды, уже использованные на этом устройстве
     * @param codeBoundToHardwareId hardware ID, к которому код уже привязан (если есть)
     * @param currentHardwareId     hardware ID текущего устройства
     */
    fun validate(
        cleanCode: String,
        remoteConfig: LicenseConfigData?,
        usedMasterCodes: Set<String>,
        codeBoundToHardwareId: String?,
        currentHardwareId: String
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

        // 2. Обычные скретч-коды коробки — контрольная сумма.
        // TODO(server): перенести алгоритм на сервер (пункт аудита #2):
        // сейчас соль и формула извлекаемы из APK.
        return if (validateBoxChecksum(cleanCode)) CodeVerdict.BoxCodeValid else CodeVerdict.Invalid
    }

    private fun validateBoxChecksum(cleanCode: String): Boolean {
        // Документированный формат скретч-кода коробки: 12-16 символов.
        // Коды короче (включая бывшие мастер-коды вроде JARVIS2026) — не box-коды.
        if (cleanCode.length < MIN_BOX_CODE_LENGTH || cleanCode.length > MAX_BOX_CODE_LENGTH) return false

        // Алгоритм контрольной суммы: сумма символов с весами.
        // Префиксные поблажки (startsWith JRV/JARVIS) УДАЛЕНЫ: они позволяли
        // любому коду с префиксом «JARVIS» проходить валидацию.
        var sum = 0
        for (i in cleanCode.indices) {
            val charCode = cleanCode[i].code
            sum += charCode * (i + 1)
        }
        // Проверяем делимость контрольной суммы
        return sum % 7 == 0
    }

    private companion object {
        const val MIN_CODE_LENGTH = 8
        const val MIN_BOX_CODE_LENGTH = 12
        const val MAX_BOX_CODE_LENGTH = 16
    }
}
