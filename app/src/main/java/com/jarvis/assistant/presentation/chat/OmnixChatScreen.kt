package com.jarvis.assistant.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.presentation.components.ConfirmationSheet
import com.jarvis.assistant.presentation.components.OmnixEmptyState
import com.jarvis.assistant.presentation.components.OmnixHairline
import com.jarvis.assistant.presentation.components.OmnixTextButton
import com.jarvis.assistant.presentation.design.OmnixTheme
import com.jarvis.assistant.presentation.state.ConfirmationRequest

/**
 * Chat — the quiet alternative to speaking (§21, §41).
 *
 * Deliberately **not** a ChatGPT clone: no avatars, no bubbles, no model
 * picker, no regenerate button, no streaming cursor. Messages are plain
 * paragraphs distinguished by weight and alignment, because chat is a
 * fallback for when the user cannot talk — not the main product surface.
 */
@Composable
fun OmnixChatScreen(
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal)
                .padding(top = spacing.lg, bottom = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.omnix_chat_title),
                style = OmnixTheme.typography.screenTitle,
                color = OmnixTheme.colors.textPrimary
            )
            if (state.messages.isNotEmpty()) {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_chat_clear),
                    onClick = onClear
                )
            }
        }

        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                contentAlignment = Alignment.Center
            ) {
                OmnixEmptyState(
                    title = stringResource(R.string.omnix_chat_empty_title),
                    description = stringResource(R.string.omnix_chat_empty_body)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageRow(message)
                }
            }
        }

        ChatComposer(
            text = state.inputText,
            enabled = !state.isSending,
            onTextChange = onInputChange,
            onSend = onSend
        )
    }

    // Confirmation reuses the one shared sheet: the tap path and the voice
    // path resolve the same pending call in the executor (§17).
    state.pendingConfirmation?.let { pending ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = pending.promptMessage,
                detail = null,
                confirmLabel = stringResource(R.string.omnix_confirm_confirm),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }
}

/**
 * One message. The speaker is conveyed by alignment and text colour rather
 * than by a coloured bubble — the screen stays a document, not an IM client.
 */
@Composable
private fun ChatMessageRow(message: Message, modifier: Modifier = Modifier) {
    val isUser = message.role == MessageRole.USER
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = stringResource(
                if (isUser) R.string.omnix_chat_you else R.string.omnix_chat_omnix
            ),
            style = OmnixTheme.typography.overline,
            color = OmnixTheme.colors.textTertiary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = message.text,
            style = OmnixTheme.typography.body,
            color = if (isUser) {
                OmnixTheme.colors.textPrimary
            } else {
                OmnixTheme.colors.textSecondary
            }
        )
    }
}

@Composable
private fun ChatComposer(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(OmnixTheme.radius.pill))
                .background(colors.surface)
                .border(
                    width = OmnixHairline,
                    color = colors.border,
                    shape = RoundedCornerShape(OmnixTheme.radius.pill)
                )
                .padding(horizontal = spacing.md, vertical = spacing.sm)
        ) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.omnix_chat_input_hint),
                    style = OmnixTheme.typography.body,
                    color = colors.textDisabled
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                singleLine = false,
                textStyle = LocalTextStyle.current.merge(
                    OmnixTheme.typography.body.copy(color = colors.textPrimary)
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.textPrimary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSend() }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OmnixTextButton(
            text = stringResource(R.string.omnix_chat_send),
            onClick = onSend,
            enabled = enabled && text.isNotBlank()
        )
    }
}
