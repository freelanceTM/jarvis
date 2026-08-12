package com.jarvis.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.core.constants.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val USER_NAME = stringPreferencesKey("user_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val HEADSET_ONLY_MODE = booleanPreferencesKey("headset_only_mode")
    }

    val userNameFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.USER_NAME] ?: "Сэр"
        }

    val systemPromptFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT] ?: AppConstants.DEFAULT_SYSTEM_PROMPT
        }

    val speechRateFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SPEECH_RATE] ?: AppConstants.DEFAULT_SPEECH_RATE
        }

    val speechPitchFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] ?: AppConstants.DEFAULT_SPEECH_PITCH
        }

    val selectedModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL] ?: AppConstants.DEFAULT_MODEL
        }

    val isHeadsetOnlyModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] ?: false
        }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USER_NAME] = name
        }
    }

    suspend fun setSystemPrompt(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT] = prompt
        }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_RATE] = rate
        }
    }

    suspend fun setSpeechPitch(pitch: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] = pitch
        }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL] = model
        }
    }

    suspend fun setHeadsetOnlyMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] = enabled
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USER_NAME] = "Сэр"
            preferences[PreferencesKeys.SYSTEM_PROMPT] = AppConstants.DEFAULT_SYSTEM_PROMPT
            preferences[PreferencesKeys.SPEECH_RATE] = AppConstants.DEFAULT_SPEECH_RATE
            preferences[PreferencesKeys.SPEECH_PITCH] = AppConstants.DEFAULT_SPEECH_PITCH
            preferences[PreferencesKeys.SELECTED_MODEL] = AppConstants.DEFAULT_MODEL
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] = false
        }
    }
}
