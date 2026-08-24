package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.preferences.SettingsDataStore
import com.jarvis.assistant.testing.ImmediateTestDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsRepositoryImplTest {
    @Test
    fun `repository exposes production data store flows without replacing values`() = runTest {
        val store = mockStore()
        val repository = SettingsRepositoryImpl(store, ImmediateTestDispatchers())

        assertEquals("Ada", repository.userNameFlow.first())
        assertEquals("system", repository.systemPromptFlow.first())
        assertEquals(1.2f, repository.speechRateFlow.first())
        assertEquals(0.8f, repository.speechPitchFlow.first())
        assertEquals("server-managed", repository.selectedModelFlow.first())
        assertEquals(true, repository.isHeadsetOnlyModeFlow.first())
        assertEquals(0.7f, repository.wakeWordSensitivityFlow.first())
    }

    @Test
    fun `all mutations delegate exact values to data store`() = runTest {
        val store = mockStore()
        coEvery { store.setUserName(any()) } returns Unit
        coEvery { store.setSystemPrompt(any()) } returns Unit
        coEvery { store.setSpeechRate(any()) } returns Unit
        coEvery { store.setSpeechPitch(any()) } returns Unit
        coEvery { store.setSelectedModel(any()) } returns Unit
        coEvery { store.setHeadsetOnlyMode(any()) } returns Unit
        coEvery { store.setWakeWordSensitivity(any()) } returns Unit
        coEvery { store.resetDefaults() } returns Unit
        val repository = SettingsRepositoryImpl(store, ImmediateTestDispatchers())

        repository.setUserName("Grace")
        repository.setSystemPrompt("safe")
        repository.setSpeechRate(1.4f)
        repository.setSpeechPitch(0.6f)
        repository.setSelectedModel("local")
        repository.setHeadsetOnlyMode(false)
        repository.setWakeWordSensitivity(0.55f)
        repository.resetDefaults()

        coVerify(exactly = 1) { store.setUserName("Grace") }
        coVerify(exactly = 1) { store.setSystemPrompt("safe") }
        coVerify(exactly = 1) { store.setSpeechRate(1.4f) }
        coVerify(exactly = 1) { store.setSpeechPitch(0.6f) }
        coVerify(exactly = 1) { store.setSelectedModel("local") }
        coVerify(exactly = 1) { store.setHeadsetOnlyMode(false) }
        coVerify(exactly = 1) { store.setWakeWordSensitivity(0.55f) }
        coVerify(exactly = 1) { store.resetDefaults() }
    }

    @Test
    fun `persistence failure is not converted into false success`() = runTest {
        val expected = java.io.IOException("disk full")
        val store = mockStore()
        coEvery { store.setSystemPrompt(any()) } throws expected
        val repository = SettingsRepositoryImpl(store, ImmediateTestDispatchers())

        var thrown: Throwable? = null
        try {
            repository.setSystemPrompt("new")
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(expected, thrown)
    }

    private fun mockStore(): SettingsDataStore = mockk {
        every { userNameFlow } returns flowOf("Ada")
        every { systemPromptFlow } returns flowOf("system")
        every { speechRateFlow } returns flowOf(1.2f)
        every { speechPitchFlow } returns flowOf(0.8f)
        every { selectedModelFlow } returns flowOf("server-managed")
        every { isHeadsetOnlyModeFlow } returns flowOf(true)
        every { wakeWordSensitivityFlow } returns flowOf(0.7f)
    }
}
