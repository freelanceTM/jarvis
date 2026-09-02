package com.jarvis.assistant.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jarvis.assistant.presentation.chat.ChatViewModel
import com.jarvis.assistant.presentation.chat.OmnixChatScreen
import com.jarvis.assistant.presentation.components.ConfirmationSheet
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.devices.DevicesScreen
import com.jarvis.assistant.presentation.history.HistoryScreen
import com.jarvis.assistant.presentation.home.HomeScreen
import com.jarvis.assistant.presentation.settings.MeScreen
import com.jarvis.assistant.presentation.settings.PrivacyScreen
import com.jarvis.assistant.presentation.settings.SECTION_ABOUT
import com.jarvis.assistant.presentation.settings.SECTION_ADVANCED
import com.jarvis.assistant.presentation.settings.SECTION_AI
import com.jarvis.assistant.presentation.settings.SECTION_APPEARANCE
import com.jarvis.assistant.presentation.settings.SECTION_LANGUAGE
import com.jarvis.assistant.presentation.settings.SECTION_NOTIFICATIONS
import com.jarvis.assistant.presentation.settings.SECTION_VOICE
import com.jarvis.assistant.presentation.settings.SettingsSectionRoute
import com.jarvis.assistant.presentation.state.OmnixViewModel
import com.jarvis.assistant.presentation.state.SystemStateType
import com.jarvis.assistant.presentation.translator.TranslatorRoute

/**
 * The OMNIX navigation graph (§20, §44).
 *
 * One `NavHost`, one persistent navigation bar, one shared [OmnixViewModel].
 * Because the state is hoisted above the host, the Core in the navigation bar
 * shows the same state as the Core on Home — from any screen (§14).
 *
 * Screen transitions are plain cross-fades: sliding panes would fight the
 * stillness the product depends on (§29).
 */
@Composable
fun OmnixNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    omnixViewModel: OmnixViewModel = hiltViewModel()
) {
    val uiState by omnixViewModel.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OmnixTheme.colors.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = OmnixDestination.Home.route,
            modifier = Modifier
                .weight(1f)
                .statusBarsPadding(),
            enterTransition = { fadeIn(tween(OmnixMotionEnter)) },
            exitTransition = { fadeOut(tween(OmnixMotionExit)) },
            popEnterTransition = { fadeIn(tween(OmnixMotionEnter)) },
            popExitTransition = { fadeOut(tween(OmnixMotionExit)) }
        ) {
            composable(OmnixDestination.Home.route) {
                HomeScreen(
                    state = uiState,
                    onClipTap = { navController.navigateSingleTop(OmnixDestination.Devices) },
                    onSystemStateAction = { omnixViewModel.refreshPermissions() }
                )
            }

            composable(OmnixDestination.History.route) {
                val chatViewModel: ChatViewModel = hiltViewModel()
                val chatState by chatViewModel.uiState.collectAsState()
                HistoryScreen(
                    messages = chatState.messages,
                    onClear = chatViewModel::clearAllHistory
                )
            }

            composable(OmnixDestination.Me.route) {
                MeScreen(
                    clip = uiState.clip,
                    isOnline = uiState.isOnline,
                    onOpenSection = { navController.navigateSingleTop(it) }
                )
            }

            composable(OmnixDestination.Chat.route) {
                val chatViewModel: ChatViewModel = hiltViewModel()
                val chatState by chatViewModel.uiState.collectAsState()
                OmnixChatScreen(
                    state = chatState,
                    onInputChange = chatViewModel::onInputTextChanged,
                    onSend = { chatViewModel.sendTextMessage() },
                    onConfirm = chatViewModel::confirmPendingAction,
                    onCancel = chatViewModel::cancelPendingAction,
                    onClear = chatViewModel::clearAllHistory
                )
            }

            composable(OmnixDestination.Translator.route) {
                TranslatorRoute(audioLevel = uiState.audioLevel)
            }

            composable(OmnixDestination.Devices.route) {
                DevicesScreen(
                    clip = uiState.clip,
                    isOnline = uiState.isOnline,
                    onBack = navController::popBackStack,
                    onConnect = { omnixViewModel.setSearching(true) }
                )
            }

            composable(OmnixDestination.Privacy.route) {
                PrivacyScreen(
                    microphoneAllowed =
                        uiState.systemState != SystemStateType.MICROPHONE_DENIED,
                    historyStored = true,
                    onBack = navController::popBackStack
                )
            }

            composable(
                route = OmnixDestination.SettingsSection.ROUTE_PATTERN,
                arguments = listOf(
                    navArgument(OmnixDestination.SettingsSection.ARG_SECTION) {
                        type = NavType.StringType
                    }
                )
            ) { entry ->
                val section = entry.arguments
                    ?.getString(OmnixDestination.SettingsSection.ARG_SECTION)
                    .orEmpty()
                SettingsSectionRoute(
                    section = section,
                    onBack = navController::popBackStack
                )
            }
        }

        // The bar is outside the NavHost, so the Core never unmounts and its
        // breathing animation is continuous across navigation (§12).
        OmnixNavigationBar(
            currentRoute = currentRoute,
            coreState = uiState.coreState,
            onNavigate = { navController.navigateSingleTop(it) }
        )
    }

    // The confirmation sheet lives above every screen: a spoken command can
    // require confirmation while the user is looking at Settings (§17).
    uiState.confirmation?.let { request ->
        ConfirmationSheet(
            request = request,
            onConfirm = omnixViewModel::confirmPendingAction,
            onCancel = omnixViewModel::cancelPendingAction
        )
    }
}

/**
 * Navigates without stacking duplicates of the primary destinations, so the
 * back button always leads out of the app rather than through a history of
 * tab switches.
 */
private fun NavHostController.navigateSingleTop(destination: OmnixDestination) {
    navigate(destination.route) {
        if (destination in OmnixDestination.primary) {
            popUpTo(OmnixDestination.Home.route) { saveState = true }
            restoreState = true
        }
        launchSingleTop = true
    }
}

private const val OmnixMotionEnter = 260
private const val OmnixMotionExit = 200

/** Section keys, re-exported so callers do not import the settings package. */
internal val settingsSections = listOf(
    SECTION_VOICE,
    SECTION_AI,
    SECTION_LANGUAGE,
    SECTION_NOTIFICATIONS,
    SECTION_APPEARANCE,
    SECTION_ABOUT,
    SECTION_ADVANCED
)
