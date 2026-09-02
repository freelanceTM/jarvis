package com.jarvis.assistant.presentation.firstrun

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.jarvis.assistant.presentation.state.ClipState
import com.jarvis.assistant.presentation.state.OmnixViewModel
import com.jarvis.assistant.presentation.state.SystemStateType

/**
 * Drives the first-run sequence with real state (§34, §67).
 *
 * The steps are advanced by genuine events — a permission actually granted, a
 * Clip actually connected, a command actually completed — never by a timer.
 */
@Composable
fun FirstRunRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OmnixViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var step by remember { mutableStateOf(FirstRunStep.Welcome) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Whatever the user chose, re-read the real permission state rather
        // than trusting the callback's map.
        viewModel.refreshPermissions()
    }

    val microphoneGranted = state.systemState != SystemStateType.MICROPHONE_DENIED

    // Searching only runs while the detection step is on screen, so the radio
    // is never scanning in the background for a screen nobody is looking at.
    LaunchedEffect(step) {
        viewModel.setSearching(step == FirstRunStep.DeviceDetection)
    }

    // A Clip that connects on its own advances the flow: the user should not
    // have to press Continue for something that already happened.
    LaunchedEffect(state.clip, step) {
        if (step == FirstRunStep.DeviceDetection && state.clip is ClipState.Connected) {
            step = FirstRunStep.ClipPairing
        }
    }

    LaunchedEffect(step) {
        if (step == FirstRunStep.Complete) onFinished()
    }

    FirstRunScreen(
        step = step,
        state = state,
        microphoneGranted = microphoneGranted,
        modifier = modifier,
        onAdvance = {
            step = step.next(clipFound = state.clip is ClipState.Connected)
        },
        onSkipDevice = { step = FirstRunStep.Microphone },
        onRequestMicrophone = {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions += Manifest.permission.BLUETOOTH_CONNECT
            }
            permissionLauncher.launch(permissions.toTypedArray())
        },
        onOpenSystemSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onEnterActivationCode = { step = FirstRunStep.Microphone },
        onSearchAgain = { viewModel.setSearching(true) }
    )
}
