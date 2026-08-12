package com.jarvis.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.core.battery.BatteryOptimizationHelper
import com.jarvis.assistant.presentation.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isSavedSuccess) {
        if (uiState.isSavedSuccess) {
            snackbarHostState.showSnackbar("Настройки успешно сохранены")
        }
    }

    Scaffold(
        containerColor = JarvisBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Настройки JARVIS",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = JarvisCyanPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. User Name Section
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Обращение к пользователю",
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisCyanPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.userName,
                        onValueChange = { viewModel.onUserNameChanged(it) },
                        label = { Text("Имя / Обращение (например: Сэр)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = outlinedTextFieldColors()
                    )
                }
            }

            // 2. Encrypted API Key Section
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "AI API Ключ",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisCyanPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = JarvisCyanSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Поддерживаются ключи OpenRouter (sk-or-...), Google Gemini (AQ.../AIza...), Groq (gsk_...) и OpenAI (sk-...).",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = { viewModel.onApiKeyChanged(it) },
                        label = { Text("sk-or-... или AQ... или gsk_...") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (uiState.isApiKeyHidden) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.toggleApiKeyVisibility() }) {
                                Icon(
                                    imageVector = if (uiState.isApiKeyHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Показать/скрыть",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = outlinedTextFieldColors()
                    )
                }
            }

            // 3. Headset-Only / Ear-First Mode Switch
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (uiState.isHeadsetOnlyMode) JarvisCyanPrimary.copy(alpha = 0.4f) else JarvisSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Headset,
                                contentDescription = null,
                                tint = JarvisCyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Работать только в наушниках",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Фоновый микрофон и Wake Word активны только при подключенной Bluetooth-гарнитуре (Ear-First режим для экономии батареи).",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }

                    Switch(
                        checked = uiState.isHeadsetOnlyMode,
                        onCheckedChange = { viewModel.onHeadsetOnlyModeChanged(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisCyanPrimary,
                            checkedTrackColor = JarvisCyanPrimary.copy(alpha = 0.4f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = JarvisSurface
                        )
                    )
                }
            }

            // 3.1 Accessibility Service Status & Activation Card
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, JarvisCyanSecondary.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccessibilityNew,
                                contentDescription = null,
                                tint = JarvisCyanSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Спец. возможности (Screen & Click)",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                                color = TextPrimary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService.isServiceRunning()) JarvisGreen.copy(alpha = 0.2f) else JarvisAmber.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService.isServiceRunning()) "АКТИВНО" else "ВЫКЛЮЧЕНО",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService.isServiceRunning()) JarvisGreen else JarvisAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Требуется для создания скриншотов, чтения экрана («Что на экране?») и нажатия кнопок без касания.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    if (!com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService.isServiceRunning()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyanPrimary),
                            border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Включить в настройках Android", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 3.2 Battery Optimization & OEM Autostart (Samsung / Xiaomi / Huawei 24/7 Mode)
            val context = androidx.compose.ui.platform.LocalContext.current
            var isBatteryIgnoring by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }

            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isBatteryIgnoring) JarvisGreen.copy(alpha = 0.3f) else JarvisAmber.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (isBatteryIgnoring) JarvisGreen else JarvisAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Фоновая работа 24/7 (${BatteryOptimizationHelper.getDeviceManufacturerName()})",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                                color = TextPrimary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isBatteryIgnoring) JarvisGreen.copy(alpha = 0.2f) else JarvisAmber.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (isBatteryIgnoring) "БЕЗ ОГРАНИЧЕНИЙ" else "ОГРАНИЧЕНО",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isBatteryIgnoring) JarvisGreen else JarvisAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Предотвращает принудительное усыпление ассистента оболочками Samsung OneUI, Xiaomi MIUI, Huawei при выключенном экране.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isBatteryIgnoring) {
                            Button(
                                onClick = {
                                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                                    isBatteryIgnoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyanPrimary),
                                border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Снять лимит батареи", fontSize = 12.sp)
                            }
                        }

                        Button(
                            onClick = {
                                BatteryOptimizationHelper.openOemAutostartSettings(context)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyanSecondary),
                            border = BorderStroke(1.dp, JarvisCyanSecondary.copy(alpha = 0.5f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Автозапуск ${BatteryOptimizationHelper.getDeviceManufacturerName()}", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 4. Automations Management Section
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, JarvisCyanPrimary.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Автоматизации (Trigger ➔ Action)",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisCyanPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.AutoMode,
                            contentDescription = null,
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = "Сценарии выполняются автоматически при наступлении аппаратных событий.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (uiState.automations.isEmpty()) {
                        Text(
                            text = "Нет активных автоматизаций. Скажите: «Джарвис, когда подключатся наушники — включи музыку».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            uiState.automations.forEach { rule ->
                                AutomationItemCard(
                                    rule = rule,
                                    onToggle = { isChecked -> viewModel.toggleAutomation(rule.ruleId, isChecked) },
                                    onDelete = { viewModel.deleteAutomation(rule.ruleId) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Voice Customization (JARVIS Male Baritone & Pitch presets)
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Голос JARVIS (Text-to-Speech)",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisCyanPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Пресеты тембра:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Voice Preset Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.onSpeechPitchChanged(0.88f)
                                viewModel.onSpeechRateChanged(1.05f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.speechPitch <= 0.90f) JarvisCyanPrimary else JarvisSurface,
                                contentColor = if (uiState.speechPitch <= 0.90f) JarvisBackground else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🎙 JARVIS", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.onSpeechPitchChanged(0.78f)
                                viewModel.onSpeechRateChanged(1.10f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.speechPitch < 0.85f) JarvisCyanPrimary else JarvisSurface,
                                contentColor = if (uiState.speechPitch < 0.85f) JarvisBackground else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⚡ Баритон", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                viewModel.onSpeechPitchChanged(1.0f)
                                viewModel.onSpeechRateChanged(1.0f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.speechPitch == 1.0f) JarvisCyanPrimary else JarvisSurface,
                                contentColor = if (uiState.speechPitch == 1.0f) JarvisBackground else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("👤 Стандарт", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Высота тона (Pitch): ${String.format(Locale.US, "%.2f", uiState.speechPitch)}x (Ниже = глубже голос)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Slider(
                        value = uiState.speechPitch,
                        onValueChange = { viewModel.onSpeechPitchChanged(it) },
                        valueRange = 0.5f..1.5f,
                        steps = 20,
                        colors = SliderDefaults.colors(thumbColor = JarvisCyanPrimary, activeTrackColor = JarvisCyanPrimary)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Скорость речи: ${String.format(Locale.US, "%.2f", uiState.speechRate)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Slider(
                        value = uiState.speechRate,
                        onValueChange = { viewModel.onSpeechRateChanged(it) },
                        valueRange = 0.7f..1.8f,
                        steps = 22,
                        colors = SliderDefaults.colors(thumbColor = JarvisCyanPrimary, activeTrackColor = JarvisCyanPrimary)
                    )
                }
            }

            // 6. System Prompt Configuration
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Системный промпт JARVIS",
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisCyanPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.systemPrompt,
                        onValueChange = { viewModel.onSystemPromptChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 7,
                        colors = outlinedTextFieldColors()
                    )
                }
            }

            // 7. Save Button
            Button(
                onClick = { viewModel.saveAllSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisGreen, contentColor = JarvisBackground)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = "Сохранить настройки", fontSize = 16.sp, color = JarvisBackground)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AutomationItemCard(
    rule: AutomationEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val triggerTitle = when (rule.triggerType) {
        "HEADPHONES_CONNECTED" -> "🎧 Подключение наушников"
        "HEADPHONES_DISCONNECTED" -> "🔌 Отключение наушников"
        "BATTERY_LOW" -> "🔋 Низкий заряд батареи"
        "WIFI_CONNECTED" -> "📶 Подключение к Wi-Fi"
        else -> rule.triggerType
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = JarvisSurface,
        border = BorderStroke(1.dp, if (rule.isEnabled) JarvisCyanPrimary.copy(alpha = 0.4f) else TextTertiary.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (rule.isEnabled) TextPrimary else TextTertiary
                )
                Text(
                    text = triggerTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisCyanSecondary
                )
                if (rule.triggerCount > 0) {
                    Text(
                        text = "Сработало: ${rule.triggerCount} раз",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = JarvisCyanPrimary,
                        checkedTrackColor = JarvisCyanPrimary.copy(alpha = 0.4f),
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = JarvisSurface
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить",
                        tint = JarvisRed.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyanPrimary,
    unfocusedBorderColor = JarvisSurface,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = JarvisCyanPrimary,
    unfocusedLabelColor = TextSecondary,
    cursorColor = JarvisCyanPrimary
)
