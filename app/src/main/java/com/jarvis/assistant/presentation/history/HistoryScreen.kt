package com.jarvis.assistant.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.presentation.components.OmnixEmptyState
import com.jarvis.assistant.presentation.components.OmnixTextButton
import com.jarvis.assistant.presentation.design.OmnixTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * History — what OMNIX has already done (§22, §39).
 *
 * The past is a quiet, scannable list, not a chat transcript: each entry is
 * what the user asked and what happened, grouped by day. There are no
 * avatars, no bubbles and no counters (§84).
 */
@Composable
fun HistoryScreen(
    messages: List<Message>,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    val grouped = groupByDay(messages)

    if (messages.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = spacing.screenHorizontal),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // An empty state states context, reason and next action — never
            // just "No data" (§19, §51).
            OmnixEmptyState(
                title = stringResource(R.string.omnix_history_empty_title),
                description = stringResource(R.string.omnix_history_empty_body)
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        item {
            Spacer(Modifier.height(spacing.lg))
            Text(
                text = stringResource(R.string.omnix_history_title),
                style = OmnixTheme.typography.screenTitle,
                color = OmnixTheme.colors.textPrimary
            )
            Spacer(Modifier.height(spacing.lg))
        }

        grouped.forEach { (dayLabel, dayMessages) ->
            item(key = "header_$dayLabel") {
                Text(
                    text = dayLabel,
                    style = OmnixTheme.typography.overline,
                    color = OmnixTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = spacing.md, bottom = spacing.xs)
                )
            }
            items(dayMessages, key = { it.id }) { message ->
                HistoryRow(message)
            }
        }

        item {
            Spacer(Modifier.height(spacing.xl))
            OmnixTextButton(
                text = stringResource(R.string.omnix_history_clear),
                onClick = onClear
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun HistoryRow(message: Message, modifier: Modifier = Modifier) {
    val spacing = OmnixTheme.spacing
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
    ) {
        Text(
            text = message.text,
            style = OmnixTheme.typography.body,
            // What the user said is primary; what OMNIX answered is secondary.
            // Weight, not colour alone, carries the distinction (§55).
            color = if (isUser) {
                OmnixTheme.colors.textPrimary
            } else {
                OmnixTheme.colors.textSecondary
            }
        )
        Text(
            text = formatTime(message.timestamp),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textDisabled
        )
    }
}

/**
 * Groups messages into Today / Yesterday / Earlier.
 *
 * The labels are resolved by the caller's locale through the resource system,
 * so the grouping is meaningful in both languages.
 */
@Composable
private fun groupByDay(messages: List<Message>): List<Pair<String, List<Message>>> {
    val today = stringResource(R.string.omnix_history_today)
    val yesterday = stringResource(R.string.omnix_history_yesterday)
    val earlier = stringResource(R.string.omnix_history_earlier)

    val now = Calendar.getInstance()
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - 24L * 60 * 60 * 1000

    val buckets = linkedMapOf(
        today to mutableListOf<Message>(),
        yesterday to mutableListOf(),
        earlier to mutableListOf()
    )
    messages.sortedByDescending { it.timestamp }.forEach { message ->
        val key = when {
            message.timestamp >= startOfToday -> today
            message.timestamp >= startOfYesterday -> yesterday
            else -> earlier
        }
        buckets.getValue(key).add(message)
    }
    return buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value.toList() }
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
