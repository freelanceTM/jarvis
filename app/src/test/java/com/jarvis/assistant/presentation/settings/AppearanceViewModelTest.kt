package com.jarvis.assistant.presentation.settings

import com.jarvis.assistant.data.preferences.OmnixExperienceStore
import com.jarvis.assistant.presentation.design.OmnixAppearance
import com.jarvis.assistant.testing.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * These pin the behaviour that the appearance settings are genuinely
 * persisted and genuinely applied — the toggles used to render but do
 * nothing, which is worse than not offering them at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val appearance = MutableStateFlow("System")
    private val nightDimming = MutableStateFlow(false)
    private val reduceMotion = MutableStateFlow(REDUCE_MOTION_SYSTEM)
    private val voiceFeedback = MutableStateFlow(true)
    private val notifyAssistant = MutableStateFlow(true)
    private val notifyDevice = MutableStateFlow(true)
    private val notifyRoutines = MutableStateFlow(true)

    private fun store(): OmnixExperienceStore {
        val store = mockk<OmnixExperienceStore>(relaxed = true)
        io.mockk.every { store.appearance } returns appearance
        io.mockk.every { store.nightDimming } returns nightDimming
        io.mockk.every { store.reduceMotionOverride } returns reduceMotion
        io.mockk.every { store.voiceFeedback } returns voiceFeedback
        io.mockk.every { store.notifyAssistant } returns notifyAssistant
        io.mockk.every { store.notifyDevice } returns notifyDevice
        io.mockk.every { store.notifyRoutines } returns notifyRoutines
        return store
    }

    @Test
    fun `stored appearance is surfaced and marked loaded`() =
        runTest(mainDispatcher.dispatcher) {
            appearance.value = "Dark"
            nightDimming.value = true
            val viewModel = AppearanceViewModel(store())

            val state = viewModel.uiState.first { it.loaded }

            assertEquals(OmnixAppearance.Dark, state.appearance)
            assertTrue(state.nightDimming)
        }

    @Test
    fun `initial state is not loaded so the first frame can be held`() =
        runTest(mainDispatcher.dispatcher) {
            // Guards against the light-flash-on-cold-start regression.
            assertFalse(AppearanceViewModel(store()).uiState.value.loaded)
        }

    @Test
    fun `an unknown stored appearance falls back to System`() =
        runTest(mainDispatcher.dispatcher) {
            appearance.value = "Sepia"
            val viewModel = AppearanceViewModel(store())

            val state = viewModel.uiState.first { it.loaded }

            assertEquals(OmnixAppearance.System, state.appearance)
        }

    @Test
    fun `reduced motion defers to the system unless explicitly overridden`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = AppearanceViewModel(store())
            assertNull(viewModel.uiState.first { it.loaded }.reducedMotion)

            reduceMotion.value = REDUCE_MOTION_ON
            assertEquals(
                true,
                viewModel.uiState.first { it.reduceMotionOverride == REDUCE_MOTION_ON }
                    .reducedMotion
            )

            reduceMotion.value = REDUCE_MOTION_OFF
            assertEquals(
                false,
                viewModel.uiState.first { it.reduceMotionOverride == REDUCE_MOTION_OFF }
                    .reducedMotion
            )
        }

    @Test
    fun `changing appearance writes it to the store`() =
        runTest(mainDispatcher.dispatcher) {
            val store = store()
            val viewModel = AppearanceViewModel(store)

            viewModel.setAppearance(OmnixAppearance.Light)
            advanceUntilIdle()

            coVerify { store.setAppearance("Light") }
        }

    @Test
    fun `notification preferences are observed and written`() =
        runTest(mainDispatcher.dispatcher) {
            val store = store()
            val viewModel = AppearanceViewModel(store)
            notifyDevice.value = false

            val state = viewModel.uiState.first { !it.notifyDevice }
            assertTrue(state.notifyAssistant)
            assertFalse(state.notifyDevice)

            viewModel.setNotifyRoutines(false)
            advanceUntilIdle()
            coVerify { store.setNotifyRoutines(false) }
        }
}
