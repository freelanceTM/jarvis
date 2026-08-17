package com.jarvis.assistant.presentation.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.components.JarvisOrbVisualizer
import com.jarvis.assistant.presentation.components.StatusBadgeRow
import com.jarvis.assistant.presentation.theme.*
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode

@Composable
fun MainScreen(
    onNavigateToChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToInterpreter: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.onEvent(MainUiEvent.ToggleBackgroundService)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is MainUiEffect.NavigateToSettings -> onNavigateToSettings()
                is MainUiEffect.ShowToast -> snackbarHostState.showSnackbar(effect.message)
                is MainUiEffect.RequestServicePermissions -> {
                    val list = mutableListOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        list.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    permissionLauncher.launch(list.toTypedArray())
                }
            }
        }
    }

    Scaffold(
        containerColor = JarvisBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "JARVIS",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 26.sp),
                        color = JarvisCyanPrimary
                    )
                    Text(
                        text = "v0.6 • Foreground Voice Service",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onNavigateToInterpreter,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JarvisSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = stringResource(R.string.sinhronnyy_perevodchik_v_uhe),
                            tint = JarvisCyanPrimary
                        )
                    }

                    IconButton(
                        onClick = onNavigateToChat,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JarvisSurface)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = stringResource(R.string.rezhim_chata),
                            tint = JarvisCyanPrimary
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JarvisSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nastroyki),
                            tint = TextSecondary
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Status Indicators (Network, Headset, Active Listening)
            StatusBadgeRow(
                isMicActive = uiState.orchestratorMode != OrchestratorMode.PAUSED_CALL_OR_SLEEP,
                isOnline = uiState.isOnline,
                isBluetoothConnected = uiState.isBluetoothConnected
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Animated Arc Reactor Core
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                JarvisOrbVisualizer(
                    assistantState = uiState.assistantState,
                    rmsDb = uiState.liveRmsDb
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Status Text Banner
            WakeWordStatusBanner(uiState.orchestratorMode, uiState.assistantState, uiState.isBluetoothConnected)

            Spacer(modifier = Modifier.height(18.dp))

            // 4. Primary Foreground Service Launch / Stop Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val required = mutableListOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            required.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            required.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }

                        val missing = required.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }

                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing.toTypedArray())
                        } else {
                            viewModel.onEvent(MainUiEvent.ToggleBackgroundService)
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isBackgroundServiceActive) JarvisRed else JarvisGreen,
                        contentColor = JarvisBackground
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(56.dp)
                        .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = JarvisCyanGlow, spotColor = JarvisCyanPrimary)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (uiState.isBackgroundServiceActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = JarvisBackground,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isBackgroundServiceActive) stringResource(R.string.ostanovit_jarvis) else stringResource(R.string.zapustit_jarvis),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = JarvisBackground
                        )
                    }
                }

                // Кнопка быстрого перехода в Чат
                Button(
                    onClick = onNavigateToChat,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisSurface,
                        contentColor = JarvisCyanPrimary
                    ),
                    border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(56.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.chat), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Query / Answer Card with Barge-in Interrupt Hint
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                border = BorderStroke(1.dp, JarvisSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_v_realnom_vremeni),
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisCyanPrimary
                        )

                        if (uiState.orchestratorMode == OrchestratorMode.TTS_SPEAKING) {
                            Text(
                                text = stringResource(R.string.prervat_dzharvis_stop),
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (uiState.lastUserQuery.isNotEmpty()) {
                            stringResource(R.string.vy_s, uiState.lastUserQuery)
                        } else {
                            stringResource(R.string.skazhite_dzharvis_v_naushnik_ili_nazhmite_zapustit_jarvis)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.lastUserQuery.isNotEmpty()) TextPrimary else TextTertiary
                    )

                    if (uiState.lastAssistantResponse.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(JarvisSurface)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.jarvis_s, uiState.lastAssistantResponse),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WakeWordStatusBanner(mode: OrchestratorMode, state: VoiceAssistantState, isHeadsetConnected: Boolean) {
    val (label, color) = when (mode) {
        OrchestratorMode.STANDBY_WAKE_WORD -> {
            if (isHeadsetConnected) stringResource(R.string.jarvis_slushaet_v_naushnike_skazhite_dzharvis) to JarvisCyanPrimary
            else stringResource(R.string.podklyuchite_naushniki_dlya_raboty) to JarvisAmber
        }
        OrchestratorMode.VERIFYING_KEYWORD -> stringResource(R.string.analiz) to JarvisCyanPrimary
        OrchestratorMode.LISTENING_USER_QUERY -> stringResource(R.string.slushayu_vash_zapros) to JarvisGreen
        OrchestratorMode.CONTINUOUS_CONVERSATION -> stringResource(R.string.slushayu_prodolzhenie_govorite_bez_dzharvis) to JarvisGreen
        OrchestratorMode.AI_THINKING -> stringResource(R.string.vypolnenie_komandy) to JarvisCyanSecondary
        OrchestratorMode.TTS_SPEAKING -> stringResource(R.string.ozvuchivanie_skazhite_stop_dlya_otmeny) to JarvisCyanPrimary
        OrchestratorMode.AWAITING_CONFIRMATION -> stringResource(R.string.ozhidanie_podtverzhdeniya_skazhite_da_ili_net) to JarvisAmber
        OrchestratorMode.LIVE_EAR_INTERPRETER -> stringResource(R.string.sinhronnyy_perevodchik_v_uhe_slushayu_sobesednika) to JarvisCyanPrimary
        OrchestratorMode.PAUSED_CALL_OR_SLEEP -> {
            if (!isHeadsetConnected) stringResource(R.string.naushniki_otklyucheny_pauza) to JarvisAmber
            else stringResource(R.string.energosberezhenie_servis_ostanovlen) to TextTertiary
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = JarvisSurface.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                color = color,
                textAlign = TextAlign.Center
            )
        }
    }
}
