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

interface SecurityManager {
    fun getApiKey(): String
    fun saveApiKey(apiKey: String)
    fun clearApiKey()
    fun hasValidApiKey(): Boolean
    val apiKeyFlow: Flow<String>
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

    private val _apiKeyFlow = MutableStateFlow(getApiKey())
    override val apiKeyFlow: Flow<String> = _apiKeyFlow.asStateFlow()

    override fun getApiKey(): String {
        return securePrefs.getString(AppConstants.KEY_API_TOKEN, "").orEmpty()
    }

    override fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        securePrefs.edit().putString(AppConstants.KEY_API_TOKEN, trimmed).apply()
        _apiKeyFlow.value = trimmed
    }

    override fun clearApiKey() {
        securePrefs.edit().remove(AppConstants.KEY_API_TOKEN).apply()
        _apiKeyFlow.value = ""
    }

    override fun hasValidApiKey(): Boolean {
        val key = getApiKey()
        return key.isNotEmpty() && key.length >= 10
    }
}
