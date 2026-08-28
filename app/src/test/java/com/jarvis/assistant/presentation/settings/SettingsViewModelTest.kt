package com.jarvis.assistant.presentation.settings

import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.core.license.ActivationResult
import com.jarvis.assistant.core.license.LicenseInfo
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.core.license.LicenseRefreshResult
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.repository.SettingsRepository
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SaveSettingsUseCase
import com.jarvis.assistant.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `initial settings license token and automations are observed`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSettingsRepository()
            val security = FakeSecurityManager(VALID_TOKEN)
            val license = FakeLicenseManager(LicenseInfo(true, planId = "pro"))
            val automation = AutomationEntity(
                ruleId = "morning",
                name = "Morning",
                triggerType = "TIME_SCHEDULE",
                actionsJson = "[]"
            )
            val dao = mockDao(listOf(automation))

            val viewModel = viewModel(repository, security, dao, license)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Ada", state.userName)
            assertEquals(VALID_TOKEN, state.accessToken)
            assertEquals("pro", state.licenseInfo?.planId)
            assertEquals(listOf(automation), state.automations)
        }

    @Test
    fun `invalid access token blocks every persistence write`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSettingsRepository()
            val security = FakeSecurityManager()
            val viewModel = viewModel(repository, security, mockDao(), FakeLicenseManager())
            advanceUntilIdle()
            viewModel.onUserNameChanged("Grace")
            viewModel.onAccessTokenChanged("invalid token with whitespace")

            viewModel.saveAllSettings()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAccessTokenInvalid)
            assertFalse(viewModel.uiState.value.isSavedSuccess)
            assertEquals(0, security.saveCalls)
            assertTrue(repository.writes.isEmpty())
        }

    @Test
    fun `valid save persists exact state and reports success`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSettingsRepository()
            val security = FakeSecurityManager()
            val viewModel = viewModel(repository, security, mockDao(), FakeLicenseManager())
            advanceUntilIdle()
            viewModel.onUserNameChanged("Grace")
            viewModel.onSystemPromptChanged("safe system")
            viewModel.onSpeechRateChanged(1.3f)
            viewModel.onSpeechPitchChanged(0.7f)
            viewModel.onAccessTokenChanged("  $VALID_TOKEN  ")

            viewModel.saveAllSettings()
            advanceUntilIdle()

            assertEquals(VALID_TOKEN, security.getAccessToken())
            assertEquals(1, security.saveCalls)
            assertTrue("name" to "Grace" in repository.writes)
            assertTrue("system" to "safe system" in repository.writes)
            assertTrue("rate" to 1.3f in repository.writes)
            assertTrue("pitch" to 0.7f in repository.writes)
            assertTrue(viewModel.uiState.value.isSavedSuccess)
        }

    @Test
    fun `automation commands and immediate voice settings delegate once`() =
        runTest(mainDispatcher.dispatcher) {
            val repository = FakeSettingsRepository()
            val dao = mockDao()
            val viewModel = viewModel(repository, FakeSecurityManager(), dao, FakeLicenseManager())
            advanceUntilIdle()

            viewModel.toggleAutomation("r1", false)
            viewModel.deleteAutomation("r2")
            viewModel.onHeadsetOnlyModeChanged(true)
            viewModel.onWakeWordSensitivityChanged(0.8f)
            advanceUntilIdle()

            coVerify(exactly = 1) { dao.toggleEnabled("r1", false) }
            coVerify(exactly = 1) { dao.deleteAutomation("r2") }
            assertTrue("headset" to true in repository.writes)
            assertTrue("sensitivity" to 0.8f in repository.writes)
        }

    private fun viewModel(
        repository: FakeSettingsRepository,
        security: FakeSecurityManager,
        dao: AutomationDao,
        license: FakeLicenseManager
    ) = SettingsViewModel(
        GetSettingsUseCase(repository),
        SaveSettingsUseCase(repository),
        security,
        dao,
        license
    )

    private fun mockDao(rules: List<AutomationEntity> = emptyList()): AutomationDao = mockk {
        every { getAllAutomationsStream() } returns flowOf(rules)
        coEvery { toggleEnabled(any(), any()) } returns Unit
        coEvery { deleteAutomation(any<String>()) } returns Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val userNameFlow = flowOf("Ada")
        override val systemPromptFlow = flowOf("system")
        override val speechRateFlow = flowOf(1.1f)
        override val speechPitchFlow = flowOf(0.9f)
        override val selectedModelFlow = flowOf("server-managed")
        override val isHeadsetOnlyModeFlow = flowOf(false)
        override val wakeWordSensitivityFlow = flowOf(0.65f)
        val writes = mutableListOf<Pair<String, Any>>()

        override suspend fun setSystemPrompt(prompt: String) { writes += "system" to prompt }
        override suspend fun setSpeechRate(rate: Float) { writes += "rate" to rate }
        override suspend fun setSpeechPitch(pitch: Float) { writes += "pitch" to pitch }
        override suspend fun setUserName(name: String) { writes += "name" to name }
        override suspend fun setSelectedModel(model: String) { writes += "model" to model }
        override suspend fun setHeadsetOnlyMode(enabled: Boolean) { writes += "headset" to enabled }
        override suspend fun setWakeWordSensitivity(sensitivity: Float) { writes += "sensitivity" to sensitivity }
        override suspend fun resetDefaults() { writes += "reset" to true }
    }

    private class FakeSecurityManager(initial: String = "") : SecurityManager {
        private val token = MutableStateFlow(initial)
        var saveCalls = 0
        override fun getAccessToken(): String = token.value
        override fun saveAccessToken(token: String) { saveCalls++; this.token.value = token }
        override fun clearAccessToken() { token.value = "" }
        override fun hasValidAccessToken(): Boolean = token.value.length >= 32
        override val accessTokenFlow: Flow<String> = token
    }

    private class FakeLicenseManager(
        initial: LicenseInfo = LicenseInfo(false)
    ) : LicenseManager {
        private val state = MutableStateFlow(initial)
        override val licenseFlow: Flow<LicenseInfo> = state
        override fun getLicenseInfo(): LicenseInfo = state.value
        override fun isActivatedAndValid(): Boolean = state.value.isActivated && !state.value.isExpired
        override suspend fun refreshFromServer(): LicenseRefreshResult = LicenseRefreshResult.Invalid
        override suspend fun activateWithCode(code: String): ActivationResult = ActivationResult.InvalidCode("invalid")
    }

    private companion object {
        const val VALID_TOKEN = "settings-token-abcdefghijklmnopqrstuvwxyz"
    }
}
