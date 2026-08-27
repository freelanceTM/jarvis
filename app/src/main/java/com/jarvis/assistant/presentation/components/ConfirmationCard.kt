package com.jarvis.assistant.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.presentation.theme.JarvisAmber
import com.jarvis.assistant.presentation.theme.JarvisCardBackground
import com.jarvis.assistant.presentation.theme.JarvisGreen
import com.jarvis.assistant.presentation.theme.TextPrimary
import com.jarvis.assistant.presentation.theme.TextSecondary

/**
 * Карточка Confirmation Gate для текстового чата.
 *
 * Показывается, когда действие (SMS, звонок и др.) требует подтверждения:
 *  - текст запроса подтверждения;
 *  - кнопка «Подтвердить» — выполняет отложенное действие через
 *    ToolExecutor.executeWithBypass (без повторного гейта);
 *  - кнопка «Отмена» — очищает отложенный вызов, ничего не выполняется.
 */
@Composable
fun ConfirmationCard(
    prompt: String,
    isExecuting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.podtverzhdenie_deystviya),
    confirmLabel: String = stringResource(R.string.podtverdit),
    cancelLabel: String = stringResource(R.string.otmena),
    tintColor: Color = JarvisAmber,
    confirmContainerColor: Color = JarvisGreen,
    confirmContentColor: Color = Color(0xFF05280F)
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(JarvisCardBackground)
            .border(1.dp, tintColor.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = tintColor
            )
        }

        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = TextPrimary
        )

        if (isExecuting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = JarvisGreen,
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.vypolnyayu),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text(cancelLabel, fontSize = 14.sp)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmContainerColor,
                        contentColor = confirmContentColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(confirmLabel, fontSize = 14.sp)
                }
            }
        }
    }
}
