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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.presentation.components.JarvisOrbVisualizer
import com.jarvis.assistant.presentation.components.StatusBadgeRow
import com.jarvis.assistant.presentation.theme.*
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode

@Composable
fun MainScreen(
    onNavigateToChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
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
                    val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
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
                        text = "v0.2 • Hands-Free & Wake Word",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onNavigateToChat,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JarvisSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "История",
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
                            contentDescription = "Настройки",
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

            // 1. Status Indicators (Network, Single Earbud Bluetooth SCO, Active Listening)
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
            WakeWordStatusBanner(uiState.orchestratorMode, uiState.assistantState)

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Hands-Free Background Voice Mode Switch Button
            Button(
                onClick = {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasAudio) {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    } else {
                        viewModel.onEvent(MainUiEvent.ToggleBackgroundService)
                    }
                },
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isBackgroundServiceActive) JarvisGreen else JarvisCyanPrimary,
                    contentColor = JarvisBackground
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = JarvisCyanGlow, spotColor = JarvisCyanPrimary)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isBackgroundServiceActive) Icons.Default.Hearing else Icons.Default.MicNone,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (uiState.isBackgroundServiceActive) "Фоновый режим: АКТИВЕН (Скажите «Джарвис»)" else "Включить голосовой режим (Без кнопок)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                            text = "Диалог в реальном времени:",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisCyanPrimary
                        )

                        if (uiState.orchestratorMode == OrchestratorMode.TTS_SPEAKING) {
                            Text(
                                text = "Прервать: «Джарвис, стоп»",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (uiState.lastUserQuery.isNotEmpty()) {
                            "Вы: ${uiState.lastUserQuery}"
                        } else {
                            "Скажите «Джарвис» вслух или через один наушник..."
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
                            text = "JARVIS: ${uiState.lastAssistantResponse}",
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
fun WakeWordStatusBanner(mode: OrchestratorMode, state: VoiceAssistantState) {
    val (label, color) = when (mode) {
        OrchestratorMode.STANDBY_WAKE_WORD -> "● Ожидание фразы «Джарвис» (Hands-Free)" to JarvisCyanPrimary
        OrchestratorMode.LISTENING_USER_QUERY -> "● Слушаю ваш запрос..." to JarvisGreen
        OrchestratorMode.AI_THINKING -> "● Генерация ответа через AI..." to JarvisCyanSecondary
        OrchestratorMode.TTS_SPEAKING -> "● Озвучивание (скажите «Стоп» для отмены)" to JarvisCyanPrimary
        OrchestratorMode.PAUSED_CALL_OR_SLEEP -> "● Энергосбережение / Пауза" to TextTertiary
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
