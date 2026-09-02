package com.jarvis.assistant.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.ClipState
import com.jarvis.assistant.presentation.state.ConfirmationRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Semantics of the shared OMNIX components.
 *
 * These assert what the user can read and press, not how it is drawn, so the
 * tests survive visual tuning.
 */
@RunWith(AndroidJUnit4::class)
class ComponentSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(voiceEnabled: Boolean = false) = ConfirmationRequest(
        title = "Send this message to Alex?",
        detail = "On my way",
        confirmLabel = context.getString(R.string.omnix_confirm_confirm),
        cancelLabel = context.getString(R.string.omnix_cancel),
        voiceEnabled = voiceEnabled
    )

    @Test
    fun confirmationSheetShowsWhatWillHappenAndConfirmsOnce() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            OmnixTheme {
                ConfirmationSheet(
                    request = request(),
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ }
                )
            }
        }

        // The sheet must state the action and its exact content, never
        // "Execute?" (§17).
        compose.onNodeWithText("Send this message to Alex?").assertIsDisplayed()
        compose.onNodeWithText("On my way").assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.omnix_confirm_confirm)).performClick()
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)
    }

    @Test
    fun confirmationSheetCancelDoesNotConfirm() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            OmnixTheme {
                ConfirmationSheet(
                    request = request(),
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ }
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.omnix_cancel)).performClick()
        assertEquals(0, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun clipStatusBarReportsDisconnectedInPlainLanguage() {
        compose.setContent {
            OmnixTheme {
                ClipStatusBar(clip = ClipState.Disconnected(), isOnline = true)
            }
        }

        compose.onNodeWithText(context.getString(R.string.omnix_status_clip_disconnected))
            .assertIsDisplayed()
    }

    @Test
    fun clipStatusBarReportsConnected() {
        compose.setContent {
            OmnixTheme {
                ClipStatusBar(clip = ClipState.Connected(deviceName = "Clip"), isOnline = true)
            }
        }

        compose.onNodeWithText(context.getString(R.string.omnix_status_clip_connected))
            .assertIsDisplayed()
    }
}
