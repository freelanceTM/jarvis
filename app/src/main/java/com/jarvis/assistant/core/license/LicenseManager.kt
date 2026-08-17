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
    data class AlreadyExpired(val message: String) : ActivationResult
}

interface LicenseManager {
    val licenseFlow: Flow<LicenseInfo>
    fun getLicenseInfo(): LicenseInfo
    fun isActivatedAndValid(): Boolean
    suspend fun activateWithCode(code: String): ActivationResult
    fun extendSubscription(days: Int = 30): LicenseInfo
    fun resetLicense()
}

@Singleton
class LicenseManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteConfig: LicenseRemoteConfig,
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
        private const val KEY_USED_MASTER_CODES = "used_master_codes"
        private const val KEY_MASTER_CODE_HARDWARE = "master_code_hardware_id"

        // Срок действия первичного кода из коробки: 30 дней
        private const val DEFAULT_TRIAL_DAYS = 30
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
     * Мастер-коды НЕ зашиты в приложение: они проверяются через удалённый
     * конфиг ([LicenseRemoteConfig]) и могут быть отозваны без обновления APK.
     * Обычные скретч-коды коробки проверяются контрольной суммой на клиенте
     * (TODO(server): перенести на сервер).
     */
    override suspend fun activateWithCode(code: String): ActivationResult {
        val cleanCode = code.trim().uppercase()
            .replace(" ", "")
            .replace("-", "")

        // 1. Валидация: удалённый конфиг (мастер-коды) + контрольная сумма (box-коды)
        val remote = remoteConfig.fetch()
        val hardwareId = getDeviceHardwareId()
        val verdict = validator.validate(
            cleanCode = cleanCode,
            remoteConfig = remote,
            serverValidator = serverValidator,
            currentHardwareId = hardwareId,
            usedMasterCodes = getUsedMasterCodes(),
            codeBoundToHardwareId = securePrefs.getString(KEY_MASTER_CODE_HARDWARE, null)
        )

        when (verdict) {
            is LicenseCodeValidator.CodeVerdict.Invalid ->
                return ActivationResult.InvalidCode(
                    context.getString(R.string.nevernyy_kod_aktivacii)
                )

            is LicenseCodeValidator.CodeVerdict.MasterCodeAlreadyUsed ->
                return ActivationResult.InvalidCode(
                    context.getString(R.string.kod_uzhe_ispolzovan)
                )

            is LicenseCodeValidator.CodeVerdict.MasterCodeValid -> {
                // Одноразовость мастер-кода на этом устройстве (cross-device — TODO(server)).
                markMasterCodeUsed(cleanCode, hardwareId)
            }

            is LicenseCodeValidator.CodeVerdict.BoxCodeValid -> Unit
        }

        // 2. Генерация лицензии на 30 дней
        val now = System.currentTimeMillis()
        val expiry = now + (DEFAULT_TRIAL_DAYS * DAY_IN_MS)

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

    /**
     * Продлевает ежемесячную подписку (50 манат / месяц)
     */
    override fun extendSubscription(days: Int): LicenseInfo {
        val current = loadLicenseFromStorage()
        val now = System.currentTimeMillis()
        val baseExpiry = if (current.expiryDate > now) current.expiryDate else now
        val newExpiry = baseExpiry + (days * DAY_IN_MS)

        securePrefs.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putLong(KEY_EXPIRY_DATE, newExpiry)
            .apply()

        val updated = loadLicenseFromStorage()
        _licenseFlow.value = updated
        return updated
    }

    override fun resetLicense() {
        securePrefs.edit().clear().apply()
        _licenseFlow.value = loadLicenseFromStorage()
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
        val isExpired = isActivated && (now > expDate)

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

    // ------------------------------------------------------- мастер-коды (удалённый конфиг)

    private fun getUsedMasterCodes(): Set<String> =
        securePrefs.getStringSet(KEY_USED_MASTER_CODES, emptySet()) ?: emptySet()

    /** Отмечает мастер-код использованным и привязывает к hardware ID этого устройства. */
    private fun markMasterCodeUsed(code: String, hardwareId: String) {
        val updated = (getUsedMasterCodes() + code).toMutableSet()
        securePrefs.edit()
            .putStringSet(KEY_USED_MASTER_CODES, updated)
            .putString(KEY_MASTER_CODE_HARDWARE, hardwareId)
            .apply()
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
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val manufacturer = Build.MANUFACTURER.uppercase()
            val model = Build.MODEL.uppercase()
            "JRV-$manufacturer-$model-${androidId.takeLast(6).uppercase()}"
        } catch (_: Exception) {
            "JRV-DEVICE-${UUID.randomUUID().toString().take(8).uppercase()}"
        }
    }
}
