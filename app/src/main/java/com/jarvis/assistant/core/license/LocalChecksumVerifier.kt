package com.jarvis.assistant.core.license

/**
 * ВРЕМЕННЫЙ офлайн-fallback проверки контрольной суммы скретч-кодов (пункт аудита #2).
 *
 * TODO(server): УДАЛИТЬ этот файл вместе с алгоритмом, как только серверная
 * валидация ([LicenseServerValidator]) будет развёрнута. Клиент не должен
 * содержать формулу контрольной суммы — она извлекаема реверс-инжинирингом
 * из APK, и на её основе можно генерировать валидные коды.
 *
 * Оставлен ТОЛЬКО чтобы не ломать активацию box-кодов офлайн, пока сервера
 * нет. Единственная точка использования — LicenseCodeValidator.
 */
object LocalChecksumVerifier {

    private const val MIN_BOX_CODE_LENGTH = 12
    private const val MAX_BOX_CODE_LENGTH = 16

    /**
     * Документированный формат скретч-кода коробки: 12-16 символов,
     * контрольная сумма (сумма символов с весами) кратна 7.
     */
    fun passes(cleanCode: String): Boolean {
        if (cleanCode.length < MIN_BOX_CODE_LENGTH || cleanCode.length > MAX_BOX_CODE_LENGTH) return false

        var sum = 0
        for (i in cleanCode.indices) {
            val charCode = cleanCode[i].code
            sum += charCode * (i + 1)
        }
        return sum % 7 == 0
    }
}
