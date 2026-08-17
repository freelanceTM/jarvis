package com.jarvis.assistant.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.presentation.components.ConfirmationCard
import com.jarvis.assistant.presentation.components.MessageItem
import com.jarvis.assistant.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Авто-скролл к последнему сообщению
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        containerColor = JarvisBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Чат с JARVIS",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                            color = TextPrimary
                        )
                        Text(
                            text = "Текстовый и голосовой режим диалога",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
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
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Очистить чат",
                                tint = JarvisRed
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisSurface)
            )
        },
        bottomBar = {
            Column {
                // Карточка Confirmation Gate: ожидает «Да»/«Нет» от пользователя.
                uiState.pendingConfirmation?.let { pending ->
                    ConfirmationCard(
                        prompt = pending.promptMessage,
                        isExecuting = uiState.isSending,
                        onConfirm = { viewModel.confirmPendingAction() },
                        onCancel = { viewModel.cancelPendingAction() }
                    )
                }

                // Нижняя панель ввода текста и микрофона
                Surface(
                    color = JarvisSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка голосового набора в чат
                    IconButton(
                        onClick = { viewModel.toggleVoiceDictation() },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isVoiceDictating) JarvisGreen else JarvisSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (uiState.isVoiceDictating) Icons.Default.Mic else Icons.Default.MicNone,
                            contentDescription = "Голосовой ввод",
                            tint = if (uiState.isVoiceDictating) JarvisBackground else JarvisCyanPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Поле ввода текста
                    OutlinedTextField(
                        value = uiState.inputText,
                        onValueChange = { viewModel.onInputTextChanged(it) },
                        placeholder = { Text("Спросите JARVIS...", color = TextTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyanPrimary,
                            unfocusedBorderColor = JarvisSurfaceVariant,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = JarvisCardBackground,
                            unfocusedContainerColor = JarvisCardBackground,
                            cursorColor = JarvisCyanPrimary
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.sendTextMessage() }),
                        maxLines = 4
                    )

                    // Кнопка отправки
                    IconButton(
                        onClick = { viewModel.sendTextMessage() },
                        enabled = uiState.inputText.isNotBlank() && !uiState.isSending,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (uiState.inputText.isNotBlank()) JarvisCyanPrimary else JarvisSurfaceVariant)
                    ) {
                        if (uiState.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = JarvisBackground,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить",
                                tint = if (uiState.inputText.isNotBlank()) JarvisBackground else TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.messages.isEmpty()) {
                // Пустое состояние
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(JarvisSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Чат с JARVIS готов",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Напишите вопрос или нажмите микрофон для набора",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { message ->
                        MessageItem(
                            message = message,
                            onSpeakClick = { text -> viewModel.speakMessage(text) }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = JarvisCardBackground,
            title = {
                Text(text = "Очистить чат?", color = TextPrimary)
            },
            text = {
                Text(
                    text = "Все сохраненные сообщения диалога будут удалены.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = JarvisRed)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}
