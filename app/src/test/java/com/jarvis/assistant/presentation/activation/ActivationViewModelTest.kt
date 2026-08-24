package com.jarvis.assistant.presentation.activation

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.core.license.ActivationResult
import com.jarvis.assistant.core.license.LicenseInfo
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.core.license.LicenseRefreshResult
import com.jarvis.assistant.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivationViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = mockk<Context> {
        every { getString(R.string.pozhaluysta_vvedite_kod) } returns "Введите код"
    }

    @Test
    fun `input is normalized and bounded by production view model`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = ActivationViewModel(context, FakeLicenseManager())

            viewModel.onCodeChanged(" ab_cd!ef 123456789012345678901234567890 ")

            assertEquals("ABCDEF123456789012345678901", viewModel.uiState.value.inputCode)
            assertEquals(27, viewModel.uiState.value.inputCode.length)
        }

    @Test
    fun `blank activation is rejected without calling license manager`() =
        runTest(mainDispatcher.dispatcher) {
            val manager = FakeLicenseManager()
            val viewModel = ActivationViewModel(context, manager)

            viewModel.activate()

            assertEquals("Введите код", viewModel.uiState.value.errorMessage)
            assertEquals(0, manager.activationCalls)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `successful activation exposes server license and clears loading`() =
        runTest(mainDispatcher.dispatcher) {
            val info = LicenseInfo(
                isActivated = true,
                planId = "pro",
                expiryDate = System.currentTimeMillis() + 86_400_000
            )
            val manager = FakeLicenseManager(
                activationResult = ActivationResult.Success(info, "Активировано")
            )
            val viewModel = ActivationViewModel(context, manager)
            viewModel.onCodeChanged("box-code-123")

            viewModel.activate()
            advanceUntilIdle()

            assertEquals("BOX-CODE-123", manager.lastCode)
            assertTrue(viewModel.uiState.value.isActivated)
            assertEquals(info, viewModel.uiState.value.licenseInfo)
            assertEquals("Активировано", viewModel.uiState.value.successMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `server failure is shown and does not activate UI`() =
        runTest(mainDispatcher.dispatcher) {
            val manager = FakeLicenseManager(
                activationResult = ActivationResult.ServiceUnavailable("Сервер недоступен")
            )
            val viewModel = ActivationViewModel(context, manager)
            viewModel.onCodeChanged("valid-code")

            viewModel.activate()
            advanceUntilIdle()

            assertEquals("Сервер недоступен", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isActivated)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    private class FakeLicenseManager(
        initial: LicenseInfo = LicenseInfo(isActivated = false),
        var activationResult: ActivationResult = ActivationResult.InvalidCode("invalid")
    ) : LicenseManager {
        private val state = MutableStateFlow(initial)
        var activationCalls = 0
        var lastCode: String? = null

        override val licenseFlow: Flow<LicenseInfo> = state
        override fun getLicenseInfo(): LicenseInfo = state.value
        override fun isActivatedAndValid(): Boolean = state.value.isActivated && !state.value.isExpired
        override suspend fun refreshFromServer(): LicenseRefreshResult = LicenseRefreshResult.Invalid

        override suspend fun activateWithCode(code: String): ActivationResult {
            activationCalls++
            lastCode = code
            if (activationResult is ActivationResult.Success) {
                state.value = (activationResult as ActivationResult.Success).licenseInfo
            }
            return activationResult
        }
    }
}
