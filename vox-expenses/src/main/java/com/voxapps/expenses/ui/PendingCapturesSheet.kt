package com.voxapps.expenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.ipc.PendingLlmRequestEntity
import com.voxapps.ipc.VoxLlmRequest
import java.text.DateFormat
import java.util.Date

/**
 * What the app is still waiting for, and what each waiting thing is.
 *
 * The strip above the list says how many; this says which — a spoken expense, a scanned receipt, a
 * payment notification — with when it was sent and how many times it has been re-sent since. A
 * number nobody can follow is a number that gets read once and then ignored.
 *
 * Nothing here can be cancelled: a queued capture is work the person asked for, and the answer may
 * still arrive. What they can do is ask for it to be tried again now rather than at the worker's
 * next turn, which is the thing a person on a bad connection actually wants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingCapturesSheet(
    entries: List<PendingLlmRequestEntity>,
    onRetryNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                languageManager.getString("pending_captures_title"),
                style = MaterialTheme.typography.titleLarge
            )

            if (entries.isEmpty()) {
                Text(
                    languageManager.getString("pending_captures_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            languageManager.getString(taskLabelKey(entry)),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(entry.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Only past the first: "attempt 1" is not news, and a row that says it on every
                    // capture teaches people to stop reading the column.
                    if (entry.attemptCount > 1) {
                        Text(
                            languageManager.getString("pending_capture_attempts").format(entry.attemptCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (entries.isNotEmpty()) {
                TextButton(onClick = onRetryNow, modifier = Modifier.padding(top = 4.dp)) {
                    Text(languageManager.getString("pending_captures_retry"))
                }
            }
        }
    }
}

/** What kind of capture a queued row is, read from the request it carries. */
private fun taskLabelKey(entry: PendingLlmRequestEntity): String {
    // The task string carries the request id as a trailing segment — see VoxLlmRequestQueue.
    val task = VoxLlmRequest.fromJson(entry.payloadJson)?.task.orEmpty()
    return when {
        task.startsWith(LlmTasks.EXPENSE_PARSE) -> "pending_task_expense_parse"
        task.startsWith(LlmTasks.EXPENSE_SCAN_CLEANUP) -> "pending_task_scan"
        task.startsWith(LlmTasks.NOTIFICATION_EXPENSE_PARSE) -> "pending_task_notification"
        task.startsWith(LlmTasks.EXPENSE_DEDUPLICATION) -> "pending_task_dedup"
        task.startsWith(LlmTasks.CATEGORY_DEDUPLICATION) -> "pending_task_categories"
        else -> "pending_task_other"
    }
}
