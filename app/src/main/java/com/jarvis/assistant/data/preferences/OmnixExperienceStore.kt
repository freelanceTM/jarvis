package com.jarvis.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent frontend signals that drive progressive disclosure and the
 * first-run flow (§10, §82, §2 of the specification).
 *
 * These are **real** signals written when something actually happens — never
 * demo values. Thresholds are not stored here: they live in
 * `GuidanceThresholds` so they stay configurable rather than hard-coded in UI.
 */
private val Context.omnixExperienceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "omnix_experience")

@Singleton
class OmnixExperienceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val FIRST_COMMAND_COMPLETED = booleanPreferencesKey("first_command_completed")
        val SUCCESSFUL_COMMANDS = intPreferencesKey("successful_commands")
        val FIRST_DEVICE_CONNECTED = booleanPreferencesKey("first_device_connected")
        val CLIP_LAST_SEEN_MILLIS = longPreferencesKey("clip_last_seen_millis")
        val CLIP_LAST_DEVICE_NAME = stringPreferencesKey("clip_last_device_name")
        val MICROPHONE_PROMPTED = booleanPreferencesKey("microphone_prompted")
        val NOTIFICATIONS_PROMPTED = booleanPreferencesKey("notifications_prompted")
        val APPEARANCE = stringPreferencesKey("appearance")
        val NIGHT_DIMMING = booleanPreferencesKey("night_dimming")
        val REDUCE_MOTION_OVERRIDE = stringPreferencesKey("reduce_motion_override")
        val VOICE_FEEDBACK = booleanPreferencesKey("voice_feedback")
        val NOTIFY_ASSISTANT = booleanPreferencesKey("notify_assistant")
        val NOTIFY_DEVICE = booleanPreferencesKey("notify_device")
        val NOTIFY_ROUTINES = booleanPreferencesKey("notify_routines")
    }

    private val preferences: Flow<Preferences> = context.omnixExperienceDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    /** True once the user reached Home through the first-run flow. */
    val onboardingCompleted: Flow<Boolean> =
        preferences.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    /** True after the first genuinely successful voice command. */
    val firstCommandCompleted: Flow<Boolean> =
        preferences.map { it[Keys.FIRST_COMMAND_COMPLETED] ?: false }

    /** Count of successful commands — the input to [GuidanceLevel]. */
    val successfulCommands: Flow<Int> =
        preferences.map { it[Keys.SUCCESSFUL_COMMANDS] ?: 0 }

    val firstDeviceConnected: Flow<Boolean> =
        preferences.map { it[Keys.FIRST_DEVICE_CONNECTED] ?: false }

    /** Epoch millis of the last confirmed Clip connection, or null (§23). */
    val clipLastSeenMillis: Flow<Long?> =
        preferences.map { it[Keys.CLIP_LAST_SEEN_MILLIS] }

    val clipLastDeviceName: Flow<String?> =
        preferences.map { it[Keys.CLIP_LAST_DEVICE_NAME] }

    val microphonePrompted: Flow<Boolean> =
        preferences.map { it[Keys.MICROPHONE_PROMPTED] ?: false }

    val notificationsPrompted: Flow<Boolean> =
        preferences.map { it[Keys.NOTIFICATIONS_PROMPTED] ?: false }

    /** "System" | "Light" | "Dark" (§47). */
    val appearance: Flow<String> =
        preferences.map { it[Keys.APPEARANCE] ?: "System" }

    val nightDimming: Flow<Boolean> =
        preferences.map { it[Keys.NIGHT_DIMMING] ?: false }

    /** "system" | "on" | "off" — user override of reduced motion (§29). */
    val reduceMotionOverride: Flow<String> =
        preferences.map { it[Keys.REDUCE_MOTION_OVERRIDE] ?: "system" }

    val voiceFeedback: Flow<Boolean> =
        preferences.map { it[Keys.VOICE_FEEDBACK] ?: true }

    val notifyAssistant: Flow<Boolean> = preferences.map { it[Keys.NOTIFY_ASSISTANT] ?: true }
    val notifyDevice: Flow<Boolean> = preferences.map { it[Keys.NOTIFY_DEVICE] ?: true }
    val notifyRoutines: Flow<Boolean> = preferences.map { it[Keys.NOTIFY_ROUTINES] ?: true }

    suspend fun setOnboardingCompleted(completed: Boolean) = edit {
        it[Keys.ONBOARDING_COMPLETED] = completed
    }

    /**
     * Records one genuinely successful command. Called from the interaction
     * layer only when the assistant actually completed a request.
     */
    suspend fun recordSuccessfulCommand() = edit {
        it[Keys.FIRST_COMMAND_COMPLETED] = true
        it[Keys.SUCCESSFUL_COMMANDS] = (it[Keys.SUCCESSFUL_COMMANDS] ?: 0) + 1
    }

    suspend fun recordClipConnected(deviceName: String, timestampMillis: Long) = edit {
        it[Keys.FIRST_DEVICE_CONNECTED] = true
        it[Keys.CLIP_LAST_DEVICE_NAME] = deviceName
        it[Keys.CLIP_LAST_SEEN_MILLIS] = timestampMillis
    }

    suspend fun recordClipSeen(timestampMillis: Long) = edit {
        it[Keys.CLIP_LAST_SEEN_MILLIS] = timestampMillis
    }

    suspend fun setMicrophonePrompted(prompted: Boolean) = edit {
        it[Keys.MICROPHONE_PROMPTED] = prompted
    }

    suspend fun setNotificationsPrompted(prompted: Boolean) = edit {
        it[Keys.NOTIFICATIONS_PROMPTED] = prompted
    }

    suspend fun setAppearance(value: String) = edit { it[Keys.APPEARANCE] = value }

    suspend fun setNightDimming(enabled: Boolean) = edit { it[Keys.NIGHT_DIMMING] = enabled }

    suspend fun setReduceMotionOverride(value: String) = edit {
        it[Keys.REDUCE_MOTION_OVERRIDE] = value
    }

    suspend fun setVoiceFeedback(enabled: Boolean) = edit { it[Keys.VOICE_FEEDBACK] = enabled }

    suspend fun setNotifyAssistant(enabled: Boolean) = edit { it[Keys.NOTIFY_ASSISTANT] = enabled }

    suspend fun setNotifyDevice(enabled: Boolean) = edit { it[Keys.NOTIFY_DEVICE] = enabled }

    suspend fun setNotifyRoutines(enabled: Boolean) = edit { it[Keys.NOTIFY_ROUTINES] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.omnixExperienceDataStore.edit(block)
    }
}
