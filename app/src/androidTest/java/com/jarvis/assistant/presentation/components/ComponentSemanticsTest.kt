package com.jarvis.assistant.presentation.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.theme.JarvisAssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun confirmationCardShowsPromptAndInvokesOnlySelectedAction() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            JarvisAssistantTheme {
                ConfirmationCard(
                    prompt = "Отправить тестовое действие?",
                    isExecuting = false,
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ }
                )
            }
        }

        compose.onNodeWithText("Отправить тестовое действие?").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.podtverdit)).performClick()
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)

        compose.onNodeWithText(context.getString(R.string.otmena)).performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun executingConfirmationHidesActionsAndShowsProgressState() {
        compose.setContent {
            JarvisAssistantTheme {
                ConfirmationCard("Выполнить?", true, {}, {})
            }
        }

        compose.onNodeWithText(context.getString(R.string.vypolnyayu)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.podtverdit)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.otmena)).assertCountEquals(0)
    }

    @Test
    fun statusBadgesReflectNetworkMicrophoneAndBluetoothState() {
        compose.setContent {
            JarvisAssistantTheme {
                StatusBadgeRow(
                    isMicActive = true,
                    isOnline = false,
                    isBluetoothConnected = true
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.oflayn)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mikrofon_aktiven)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.garnitura)).assertIsDisplayed()
    }
}
