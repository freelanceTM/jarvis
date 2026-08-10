package com.jarvis.assistant.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.models.AIModel
import com.jarvis.assistant.presentation.theme.JarvisBackground
import com.jarvis.assistant.presentation.theme.JarvisCardBackground
import com.jarvis.assistant.presentation.theme.JarvisCyanPrimary
import com.jarvis.assistant.presentation.theme.JarvisCyanSecondary
import com.jarvis.assistant.presentation.theme.JarvisGreen
import com.jarvis.assistant.presentation.theme.JarvisSurface
import com.jarvis.assistant.presentation.theme.TextPrimary
import com.jarvis.assistant.presentation.theme.TextSecondary
import com.jarvis.assistant.presentation.theme.TextTertiary

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
                        label = { Text("Имя / Обращение") },
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
                            text = "AI API Ключ (Encrypted)",
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
                        text = "Хранится в зашифрованном виде (AES-256 GCM) через AndroidX Security Crypto.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = { viewModel.onApiKeyChanged(it) },
                        label = { Text("sk-proj-...") },
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

            // 3. AI Model Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AI-Провайдер и Модель",
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisCyanPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelDropdownExpanded,
                        onExpandedChange = { modelDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = AIModel.fromModelId(uiState.selectedModel).displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false },
                            modifier = Modifier.background(JarvisSurface)
                        ) {
                            AIModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName, color = TextPrimary) },
                                    onClick = {
                                        viewModel.onModelSelected(model.modelId)
                                        modelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
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
                    Text(
                        text = "Задает характер, стиль ответов и модель поведения ассистента.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.systemPrompt,
                        onValueChange = { viewModel.onSystemPromptChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        maxLines = 8,
                        colors = outlinedTextFieldColors()
                    )
                }
            }

            // 5. Speech Synthesis Settings (TTS)
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBackground),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Настройки голоса (Text-to-Speech)",
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisCyanPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Скорость речи: ${String.format("%.1f", uiState.speechRate)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Slider(
                        value = uiState.speechRate,
                        onValueChange = { viewModel.onSpeechRateChanged(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = JarvisCyanPrimary,
                            activeTrackColor = JarvisCyanPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Высота тона: ${String.format("%.1f", uiState.speechPitch)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Slider(
                        value = uiState.speechPitch,
                        onValueChange = { viewModel.onSpeechPitchChanged(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = JarvisCyanPrimary,
                            activeTrackColor = JarvisCyanPrimary
                        )
                    )
                }
            }

            // 6. Save Button
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
