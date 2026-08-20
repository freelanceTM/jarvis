package com.jarvis.assistant.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.core.constants.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Защищённое хранилище секретов клиента.
 *
 * Этап 3: вместо ключей AI-провайдеров (BYOK) хранится ОДИН токен доступа
 * к JARVIS API. Ключи Groq/Gemini/OpenRouter на устройстве больше не хранятся
 * и не используются — они живут исключительно на сервере.
 */
interface SecurityManager {

    /** Токен доступа к JARVIS API (Bearer). */
    fun getAccessToken(): String

    fun saveAccessToken(token: String)

    fun clearAccessToken()

    fun hasValidAccessToken(): Boolean

    val accessTokenFlow: Flow<String>
}

@Singleton
class SecurityManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SecurityManager {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        AppConstants.SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _accessTokenFlow = MutableStateFlow(getAccessToken())
    override val accessTokenFlow: Flow<String> = _accessTokenFlow.asStateFlow()

    init {
        // Миграция Этапа 3: удаляем legacy-ключ AI-провайдера, если он остался
        // от прошлых версий. Ключи провайдеров больше не должны лежать
        // на устройстве ни в каком виде.
        if (securePrefs.contains(AppConstants.LEGACY_KEY_PROVIDER_API)) {
            securePrefs.edit().remove(AppConstants.LEGACY_KEY_PROVIDER_API).apply()
        }
    }

    override fun getAccessToken(): String =
        securePrefs.getString(AppConstants.KEY_ACCESS_TOKEN, "").orEmpty()

    override fun saveAccessToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            clearAccessToken()
            return
        }
        require(AccessTokenPolicy.isValid(trimmed)) {
            "JARVIS access token must be ${AccessTokenPolicy.MIN_LENGTH}..${AccessTokenPolicy.MAX_LENGTH} characters without whitespace"
        }
        securePrefs.edit().putString(AppConstants.KEY_ACCESS_TOKEN, trimmed).apply()
        _accessTokenFlow.value = trimmed
    }

    override fun clearAccessToken() {
        securePrefs.edit().remove(AppConstants.KEY_ACCESS_TOKEN).apply()
        _accessTokenFlow.value = ""
    }

    override fun hasValidAccessToken(): Boolean =
        AccessTokenPolicy.isValid(getAccessToken())
}
