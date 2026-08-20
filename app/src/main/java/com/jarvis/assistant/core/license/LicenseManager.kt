package com.jarvis.assistant.core.license

import com.jarvis.assistant.R
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class LicenseInfo(
    val isActivated: Boolean,
    val activationCode: String = "",
    val activationDate: Long = 0L,
    val expiryDate: Long = 0L,
    val remainingDays: Int = 0,
    val isExpired: Boolean = false,
    val hardwareSerial: String = ""
)

sealed interface ActivationResult {
    data class Success(val licenseInfo: LicenseInfo, val message: String) : ActivationResult
    data class InvalidCode(val reason: String) : ActivationResult
    data class ServiceUnavailable(val message: String) : ActivationResult
    data class AlreadyExpired(val message: String) : ActivationResult
}

interface LicenseManager {
    val licenseFlow: Flow<LicenseInfo>
    fun getLicenseInfo(): LicenseInfo
    fun isActivatedAndValid(): Boolean
    suspend fun activateWithCode(code: String): ActivationResult
}

@Singleton
class LicenseManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverValidator: LicenseServerValidator,
    private val validator: LicenseCodeValidator
) : LicenseManager {

    companion object {
        private const val PREFS_NAME = "jarvis_hardware_license"
        private const val KEY_ACTIVATED = "is_activated"
        private const val KEY_CODE = "activation_code"
        private const val KEY_ACTIVATION_DATE = "activation_date"
        private const val KEY_EXPIRY_DATE = "expiry_date"
        private const val KEY_HARDWARE_ID = "hardware_id"

        private const val DAY_IN_MS = 24L * 60 * 60 * 1000L
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _licenseFlow = MutableStateFlow(loadLicenseFromStorage())
    override val licenseFlow: Flow<LicenseInfo> = _licenseFlow.asStateFlow()

    override fun getLicenseInfo(): LicenseInfo = loadLicenseFromStorage()

    override fun isActivatedAndValid(): Boolean {
        val info = loadLicenseFromStorage()
        return info.isActivated && !info.isExpired
    }

    /**
     * Активирует приложение одноразовым кодом из инструкции к наушникам JARVIS Earclip.
     *
     * Любые коды проверяются только сервером и привязываются к hardware ID.
     * При недоступном endpoint активация fail closed и не изменяет лицензию.
     */
    override suspend fun activateWithCode(code: String): ActivationResult {
        val cleanCode = code.trim().uppercase()
            .replace(" ", "")
            .replace("-", "")

        // 1. Серверная валидация и привязка к устройству.
        val hardwareId = getDeviceHardwareId()
        val verdict = validator.validate(
            cleanCode = cleanCode,
            serverValidator = serverValidator,
            currentHardwareId = hardwareId
        )

        val licenseDays = when (verdict) {
            LicenseCodeValidator.CodeVerdict.Invalid ->
                return ActivationResult.InvalidCode(
                    context.getString(R.string.nevernyy_kod_aktivacii)
                )

            LicenseCodeValidator.CodeVerdict.ServiceUnavailable ->
                return ActivationResult.ServiceUnavailable(
                    context.getString(R.string.server_licenziy_nedostupen)
                )

            is LicenseCodeValidator.CodeVerdict.BoxCodeValid -> verdict.licenseDays
        }

        // 2. Генерация лицензии на срок, подтверждённый сервером.
        val now = System.currentTimeMillis()
        val expiry = Math.addExact(now, Math.multiplyExact(licenseDays.toLong(), DAY_IN_MS))

        securePrefs.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_CODE, formatFormattedCode(cleanCode))
            .putLong(KEY_ACTIVATION_DATE, now)
            .putLong(KEY_EXPIRY_DATE, expiry)
            .putString(KEY_HARDWARE_ID, hardwareId)
            .apply()

        val newInfo = loadLicenseFromStorage()
        _licenseFlow.value = newInfo

        return ActivationResult.Success(
            licenseInfo = newInfo,
            message = context.getString(R.string.jarvis_uspeshno_aktivirovan)
        )
    }

    private fun loadLicenseFromStorage(): LicenseInfo {
        val isActivated = securePrefs.getBoolean(KEY_ACTIVATED, false)
        val code = securePrefs.getString(KEY_CODE, "").orEmpty()
        val actDate = securePrefs.getLong(KEY_ACTIVATION_DATE, 0L)
        val expDate = securePrefs.getLong(KEY_EXPIRY_DATE, 0L)
        val hwId = securePrefs.getString(KEY_HARDWARE_ID, getDeviceHardwareId()).orEmpty()

        val now = System.currentTimeMillis()
        val remainingMs = max(0L, expDate - now)
        val remainingDays = (remainingMs / DAY_IN_MS).toInt()
        val isExpired = isActivated && (now >= expDate)

        return LicenseInfo(
            isActivated = isActivated,
            activationCode = code,
            activationDate = actDate,
            expiryDate = expDate,
            remainingDays = remainingDays,
            isExpired = isExpired,
            hardwareSerial = hwId
        )
    }

    private fun formatFormattedCode(raw: String): String {
        return if (raw.length >= 8) {
            raw.chunked(4).joinToString("-")
        } else {
            raw
        }
    }

    // ANDROID_ID используется НАМЕРЕННО: привязка лицензии к устройству —
    // требование аудита (#1: hardware ID binding). Не персональные данные.
    @Suppress("HardwareIds")
    private fun getDeviceHardwareId(): String {
        val stored = securePrefs.getString(KEY_HARDWARE_ID, null)?.takeIf { it.isNotBlank() }
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
                ?: return stored ?: newFallbackHardwareId()
            val manufacturer = Build.MANUFACTURER.uppercase(Locale.ROOT)
            val model = Build.MODEL.uppercase(Locale.ROOT)
            "JRV-$manufacturer-$model-${androidId.takeLast(6).uppercase(Locale.ROOT)}"
        } catch (_: Exception) {
            stored ?: newFallbackHardwareId()
        }
    }

    private fun newFallbackHardwareId(): String =
        "JRV-DEVICE-${UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT)}"
}
