package com.jarvis.assistant.presentation.settings

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.models.AIModel
import com.jarvis.assistant.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

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

            // 3. Voice Customization (JARVIS Male Baritone & Pitch presets)
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
                        text = "Высота тона (Pitch): ${String.format("%.2f", uiState.speechPitch)}x (Ниже = глубже голос)",
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
                        text = "Скорость речи: ${String.format("%.2f", uiState.speechRate)}x",
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

            // 4. System Prompt Configuration
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

            // 5. Save Button
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
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyanPrimary,
    unfocusedBorderColor = JarvisSurface,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = JarvisCyanPrimary,
    unfocusedLabelColor = TextSecondary,
    cursorColor = JarvisCyanPrimary
)
