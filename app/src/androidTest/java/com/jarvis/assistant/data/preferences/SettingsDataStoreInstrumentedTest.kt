package com.jarvis.assistant.data.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.core.constants.AppConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDataStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() = runBlocking {
        SettingsDataStore(context).resetDefaults()
    }

    @Test
    fun defaultsMatchProductionContract() = runBlocking {
        val store = SettingsDataStore(context)

        assertEquals("Сэр", store.userNameFlow.first())
        assertEquals(AppConstants.DEFAULT_SYSTEM_PROMPT, store.systemPromptFlow.first())
        assertEquals(AppConstants.DEFAULT_SPEECH_RATE, store.speechRateFlow.first())
        assertEquals(AppConstants.DEFAULT_SPEECH_PITCH, store.speechPitchFlow.first())
        assertEquals(AppConstants.DEFAULT_MODEL, store.selectedModelFlow.first())
        assertEquals(false, store.isHeadsetOnlyModeFlow.first())
        assertEquals(0.65f, store.wakeWordSensitivityFlow.first())
    }

    @Test
    fun updatesPersistAcrossProductionWrapperInstances() = runBlocking {
        val first = SettingsDataStore(context)
        first.setUserName("Ada")
        first.setSystemPrompt("safe system")
        first.setSpeechRate(1.25f)
        first.setSpeechPitch(0.75f)
        first.setSelectedModel("local")
        first.setHeadsetOnlyMode(true)
        first.setWakeWordSensitivity(0.8f)

        val reopened = SettingsDataStore(context)
        assertEquals("Ada", reopened.userNameFlow.first())
        assertEquals("safe system", reopened.systemPromptFlow.first())
        assertEquals(1.25f, reopened.speechRateFlow.first())
        assertEquals(0.75f, reopened.speechPitchFlow.first())
        assertEquals("local", reopened.selectedModelFlow.first())
        assertEquals(true, reopened.isHeadsetOnlyModeFlow.first())
        assertEquals(0.8f, reopened.wakeWordSensitivityFlow.first())
    }

    @Test
    fun resetOverwritesEveryUserControlledValue() = runBlocking {
        val store = SettingsDataStore(context)
        store.setUserName("Changed")
        store.setSystemPrompt("Changed")
        store.setSpeechRate(2f)
        store.setSpeechPitch(2f)
        store.setSelectedModel("changed")
        store.setHeadsetOnlyMode(true)
        store.setWakeWordSensitivity(1f)

        store.resetDefaults()

        assertEquals("Сэр", store.userNameFlow.first())
        assertEquals(AppConstants.DEFAULT_SYSTEM_PROMPT, store.systemPromptFlow.first())
        assertEquals(AppConstants.DEFAULT_SPEECH_RATE, store.speechRateFlow.first())
        assertEquals(AppConstants.DEFAULT_SPEECH_PITCH, store.speechPitchFlow.first())
        assertEquals(AppConstants.DEFAULT_MODEL, store.selectedModelFlow.first())
        assertEquals(false, store.isHeadsetOnlyModeFlow.first())
        assertEquals(0.65f, store.wakeWordSensitivityFlow.first())
    }
}
