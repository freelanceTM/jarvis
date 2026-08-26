package com.jarvis.assistant.core.license

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.R
import com.jarvis.assistant.core.security.AccessTokenPolicy
import com.jarvis.assistant.core.security.SecurityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class LicenseInfo(
    /** True only after a successful server response in this app process. */
    val isActivated: Boolean,
    val planId: String = "",
    val productId: String = "",
    val activationDate: Long = 0L,
    val expiryDate: Long = 0L,
    val remainingDays: Int = 0,
    val isExpired: Boolean = false,
    val hardwareSerial: String = "",
    val billingStatus: String = "",
    val verifiedAt: Long = 0L
)

sealed interface ActivationResult {
    data class Success(val licenseInfo: LicenseInfo, val message: String) : ActivationResult
    data class InvalidCode(val reason: String) : ActivationResult
    data class ServiceUnavailable(val message: String) : ActivationResult
    data class AlreadyExpired(val message: String) : ActivationResult
}

sealed interface LicenseRefreshResult {
    data class Valid(val licenseInfo: LicenseInfo) : LicenseRefreshResult
    data object Invalid : LicenseRefreshResult
    data object Expired : LicenseRefreshResult
    data object Revoked : LicenseRefreshResult
    data object Unauthorized : LicenseRefreshResult
    data object RateLimited : LicenseRefreshResult
    data object ServiceUnavailable : LicenseRefreshResult
}

interface LicenseManager {
    val licenseFlow: Flow<LicenseInfo>
    fun getLicenseInfo(): LicenseInfo
    /** Cached process-local result; MainActivity calls [refreshFromServer] before unlocking UI. */
    fun isActivatedAndValid(): Boolean
    suspend fun refreshFromServer(): LicenseRefreshResult
    suspend fun activateWithCode(code: String): ActivationResult
}

@Singleton
class LicenseManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverValidator: LicenseServerValidator,
    private val validator: LicenseCodeValidator,
    private val securityManager: SecurityManager
) : LicenseManager {
    companion object {
        private const val TAG = "LicenseManager"
        private const val PREFS_NAME = "jarvis_hardware_license"
        private const val KEY_ACTIVATED = "is_activated"
        private const val KEY_PLAN_ID = "plan_id"
        private const val KEY_PRODUCT_ID = "product_id"
        private const val KEY_ACTIVATION_DATE = "activation_date"
        private const val KEY_EXPIRY_DATE = "expiry_date"
        private const val KEY_HARDWARE_ID = "hardware_id"
        private const val KEY_BILLING_STATUS = "billing_status"
        private const val KEY_VERIFIED_AT = "verified_at"
        private const val LEGACY_KEY_CODE = "activation_code"
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

    // Persisted state is display-only until independently revalidated this process.
    private val _licenseFlow = MutableStateFlow(loadCachedInfo().copy(isActivated = false))
    override val licenseFlow: Flow<LicenseInfo> = _licenseFlow.asStateFlow()

    init {
        // Activation secrets must never remain on the client after migration.
        securePrefs.edit().remove(LEGACY_KEY_CODE).apply()
    }

    override fun getLicenseInfo(): LicenseInfo = _licenseFlow.value

    override fun isActivatedAndValid(): Boolean {
        val info = _licenseFlow.value
        return info.isActivated && !info.isExpired
    }

    override suspend fun refreshFromServer(): LicenseRefreshResult {
        val hardwareId = getDeviceHardwareId()
        return when (val result = serverValidator.validate(hardwareId)) {
            is ServerLicenseValidationResult.Valid -> {
                val info = persistServerRecord(result.license, hardwareId)
                LicenseRefreshResult.Valid(info)
            }
            ServerLicenseValidationResult.Expired -> {
                invalidateProcessState(expired = true)
                LicenseRefreshResult.Expired
            }
            ServerLicenseValidationResult.RevokedOrDisabled,
            ServerLicenseValidationResult.WrongDevice -> {
                invalidateProcessState(expired = false)
                securityManager.clearAccessToken()
                LicenseRefreshResult.Revoked
            }
            ServerLicenseValidationResult.PaymentRequired,
            ServerLicenseValidationResult.Invalid -> {
                invalidateProcessState(expired = false)
                LicenseRefreshResult.Invalid
            }
            ServerLicenseValidationResult.Unauthorized -> {
                invalidateProcessState(expired = false)
                securityManager.clearAccessToken()
                LicenseRefreshResult.Unauthorized
            }
            ServerLicenseValidationResult.RateLimited -> {
                invalidateProcessState(expired = false)
                LicenseRefreshResult.RateLimited
            }
            ServerLicenseValidationResult.ServiceUnavailable -> {
                invalidateProcessState(expired = false)
                LicenseRefreshResult.ServiceUnavailable
            }
        }
    }

    override suspend fun activateWithCode(code: String): ActivationResult {
        val cleanCode = code.trim().uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '-' }
        val hardwareId = getDeviceHardwareId()
        return when (val verdict = validator.redeem(cleanCode, serverValidator, hardwareId)) {
            LicenseCodeValidator.CodeVerdict.Invalid -> ActivationResult.InvalidCode(
                context.getString(R.string.nevernyy_kod_aktivacii)
            )
            LicenseCodeValidator.CodeVerdict.RateLimited -> ActivationResult.ServiceUnavailable(
                context.getString(R.string.slishkom_mnogo_popytok_aktivacii)
            )
            LicenseCodeValidator.CodeVerdict.ServiceUnavailable -> ActivationResult.ServiceUnavailable(
                context.getString(R.string.server_licenziy_nedostupen)
            )
            is LicenseCodeValidator.CodeVerdict.BoxCodeValid -> {
                val record = verdict.license
                val token = record.accessToken
                // S-04: явная проверка токена ДО сохранения. Invalid token —
                // ожидаемая невалидация (не сервисная ошибка и не crash).
                // IllegalArgumentException из isValid быть не должно (isValid
                // — чистый predicate), но мы на всякий случай отделяем
                // валидационный фейл от любых runtime/system ошибок при
                // записи в EncryptedSharedPreferences.
                if (token == null || !AccessTokenPolicy.isValid(token)) {
                    return ActivationResult.InvalidCode(
                        context.getString(R.string.nevernyy_kod_aktivacii)
                    )
                }
                return try {
                    securityManager.saveAccessToken(token)
                    val info = persistServerRecord(record, hardwareId)
                    ActivationResult.Success(
                        licenseInfo = info,
                        message = context.getString(R.string.jarvis_uspeshno_aktivirovan)
                    )
                } catch (t: Throwable) {
                    // Не пишем token в логи. Сюда можем попасть только при
                    // реальной ошибке EncryptedSharedPreferences/MasterKey.
                    Log.e(TAG, "failed to persist access token after activation", t)
                    ActivationResult.ServiceUnavailable(
                        context.getString(R.string.server_licenziy_nedostupen)
                    )
                }
            }
        }
    }

    private fun persistServerRecord(record: ServerLicenseRecord, hardwareId: String): LicenseInfo {
        val starts = record.startsAt.toEpochMilli()
        val expires = record.expiresAt.toEpochMilli()
        require(expires > starts)
        val verifiedAt = System.currentTimeMillis()
        check(
            securePrefs.edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putString(KEY_PLAN_ID, record.planId)
                .putString(KEY_PRODUCT_ID, record.productId)
                .putLong(KEY_ACTIVATION_DATE, starts)
                .putLong(KEY_EXPIRY_DATE, expires)
                .putString(KEY_HARDWARE_ID, hardwareId)
                .putString(KEY_BILLING_STATUS, record.billingStatus)
                .putLong(KEY_VERIFIED_AT, verifiedAt)
                .remove(LEGACY_KEY_CODE)
                .commit()
        ) { "Could not persist server license state" }
        return loadCachedInfo().copy(isActivated = true).also { _licenseFlow.value = it }
    }

    private fun invalidateProcessState(expired: Boolean) {
        val current = loadCachedInfo()
        securePrefs.edit().putBoolean(KEY_ACTIVATED, false).apply()
        _licenseFlow.value = current.copy(isActivated = false, isExpired = expired || current.isExpired)
    }

    private fun loadCachedInfo(): LicenseInfo {
        val serverActivated = securePrefs.getBoolean(KEY_ACTIVATED, false)
        val startsAt = securePrefs.getLong(KEY_ACTIVATION_DATE, 0L)
        val expiresAt = securePrefs.getLong(KEY_EXPIRY_DATE, 0L)
        val now = System.currentTimeMillis()
        val remainingMs = max(0L, expiresAt - now)
        return LicenseInfo(
            isActivated = serverActivated,
            planId = securePrefs.getString(KEY_PLAN_ID, "").orEmpty(),
            productId = securePrefs.getString(KEY_PRODUCT_ID, "").orEmpty(),
            activationDate = startsAt,
            expiryDate = expiresAt,
            remainingDays = (remainingMs / DAY_IN_MS).toInt(),
            isExpired = serverActivated && now >= expiresAt,
            hardwareSerial = securePrefs.getString(KEY_HARDWARE_ID, "").orEmpty(),
            billingStatus = securePrefs.getString(KEY_BILLING_STATUS, "").orEmpty(),
            verifiedAt = securePrefs.getLong(KEY_VERIFIED_AT, 0L)
        )
    }

    @Suppress("HardwareIds")
    private fun getDeviceHardwareId(): String {
        securePrefs.getString(KEY_HARDWARE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            if (androidId != null) {
                val manufacturer = Build.MANUFACTURER.uppercase(Locale.ROOT)
                val model = Build.MODEL.uppercase(Locale.ROOT)
                "JRV-$manufacturer-$model-${androidId.takeLast(12).uppercase(Locale.ROOT)}"
            } else {
                newFallbackHardwareId()
            }
        } catch (_: Exception) {
            newFallbackHardwareId()
        }
        // apply() updates the in-memory SharedPreferences snapshot synchronously;
        // subsequent calls in this process see the same stable fallback ID.
        securePrefs.edit().putString(KEY_HARDWARE_ID, generated).apply()
        return generated
    }

    private fun newFallbackHardwareId(): String =
        "JRV-DEVICE-${UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT)}"
}
