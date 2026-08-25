package com.voxapps.expenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.selection.voxSelectable
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import com.voxapps.expenses.domain.recurring.PredictedPayment
import java.text.DateFormat
import java.util.Date

/** Matches the fixed (non-theme) colors baked into ic_arrow_inward.xml/ic_arrow_outward.xml, used
 *  by the widget for the same direction glyph — keeps the in-app and widget icons visually identical. */
private val IncomeGreen = Color(0xFF4CAF50)
private val ExpenseRed = Color(0xFFD32F2F)

/** A single expense row: title/vendor, category dot, formatted total, and date. */
@Composable
fun ExpenseCard(
    expenseWithDetails: ExpenseWithDetails,
    onClick: () -> Unit,
    recurring: Boolean = false,
    /** What this record is missing, when the list is narrowed to the ones that need somebody. A
     *  card in a "needs you" list that does not say why makes the person open it to find out. */
    missing: String? = null,
    /** Picked out of the list, for something about to be done to several records at once. */
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** The bank this went through — the name of the account it points at, resolved by the caller,
     *  which is the layer that has the accounts. */
    bankName: String? = null,
    /**
     * Whether the row has to name the day as well as the hour.
     *
     * False wherever the list already groups by day: the date under a day heading is the heading
     * again, in smaller type, on every row beneath it. The time is never redundant — two payments on
     * one day are told apart by it — so it is always there.
     */
    showDay: Boolean = true
) {
    val expense = expenseWithDetails.expense
    val category = expenseWithDetails.category

    val context = LocalContext.current
    val attachmentDao = remember { (context.applicationContext as ExpensesApplication).container.attachmentDao }
    val hasAttachments by remember(expense.id) {
        attachmentDao.observeFor(ExpensesAttachments.RECORD_TYPE, expense.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .voxSelectable(selected = selected, onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The icon takes the dot's place rather than sitting beside it: both say which category
            // this is, and a row carrying two answers to one question is a row that reads slower.
            if (category?.icon != null) {
                Text(category.icon, fontSize = 17.sp)
            } else if (category != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(CategoryColors.fromStored(category.colorArgb))
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (category != null) 10.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor ?: "—",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (hasAttachments.isNotEmpty()) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(start = 4.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The arrival of a recurring payment keeps saying so — it is an ordinary expense
                    // now, but knowing it is the monthly one is most of what you wanted to know.
                    if (recurring) {
                        Icon(
                            Icons.Filled.Repeat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp)
                        )
                    }
                    if (!expense.receiptImageName.isNullOrBlank()) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp)
                        )
                    }
                    if (expense.isStub || ExpenseAmountMismatch.isGrossMismatch(expense.totalAmount, expenseWithDetails.items.sumOf { it.quantity * it.unitPrice })) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp)
                        )
                    }
                    missing?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // What the row is filed under, in the order it is asked: when, where the money
                    // came from, what it counts as. Anything that has nothing to say is left out
                    // rather than printed empty.
                    val filedAs = listOfNotNull(
                        if (showDay) {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(expense.dateTime))
                        } else {
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(expense.dateTime))
                        },
                        bankName?.takeIf { it.isNotBlank() },
                        category?.name
                    )
                    if (filedAs.isNotEmpty()) {
                        Text(
                            text = filedAs.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Icon(
                imageVector = if (expense.direction == TransactionDirection.INCOMING) {
                    Icons.AutoMirrored.Filled.CallReceived
                } else {
                    Icons.AutoMirrored.Filled.CallMade
                },
                contentDescription = null,
                tint = if (expense.direction == TransactionDirection.INCOMING) IncomeGreen else ExpenseRed,
                modifier = Modifier.size(16.dp).padding(start = 8.dp)
            )
            Text(
                text = formatAmount(expense.totalAmount, expense.currencyCode),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * A payment that is expected but has not arrived.
 *
 * Drawn as an outline with nothing inside it — no card, no elevation, a dashed contour. That is the
 * whole point of the treatment: a real expense is a solid surface because something happened, and
 * this one has to be legible at a glance as the absence of that. It is deliberately not clickable
 * either; there is no record here to open, and offering an editor would invite a person to fill in a
 * payment that has not been made.
 */
@Composable
fun PredictedPaymentCard(predicted: PredictedPayment, categoryColorArgb: Long?) {
    val outline = if (predicted.overdue) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.outline
    val languageManager = LocalLanguageManager.current
    val dashes = with(LocalDensity.current) { floatArrayOf(6.dp.toPx(), 5.dp.toPx()) }
    val corner = with(LocalDensity.current) { 12.dp.toPx() }
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(dashes))
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (categoryColorArgb != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(CategoryColors.fromStored(categoryColorArgb).copy(alpha = 0.5f))
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (categoryColorArgb != null) 10.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = null,
                        tint = outline,
                        modifier = Modifier.size(14.dp).padding(end = 4.dp)
                    )
                    Text(
                        text = predicted.vendorLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(predicted.dueAtMillis)).let {
                        languageManager.getString(
                            if (predicted.overdue) "recurring_overdue_since" else "recurring_expected_on"
                        ).format(it)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = outline
                )
            }
            // The figure is last month's, and it is labelled as a guess rather than shown as a total:
            // a bill that has not arrived has no amount, and printing one as if it had would put a
            // number nobody owes next to numbers somebody paid.
            Text(
                text = predicted.expectedAmount
                    ?.let { "≈ " + formatAmount(it, predicted.currency ?: "") }
                    ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Simple, locale-agnostic amount formatting — "123.45 RON" — avoids NumberFormat currency-symbol
 *  guessing for currency codes that may not map to the device's default locale. */
fun formatAmount(amount: Double, currencyCode: String): String {
    val rounded = "%.2f".format(amount)
    return "$rounded $currencyCode"
}

/**
 * A figure with no currency after it, for a bracket rather than a record.
 *
 * A bracket spans whatever the list holds, and a list can hold more than one currency — so naming
 * one would be claiming something about records filed in the others. Whole numbers lose their
 * decimals, because a boundary is a round number by construction and "50.00 – 100.00" is two
 * decimals of nothing.
 */
fun formatAmountPlain(amount: Double): String =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
