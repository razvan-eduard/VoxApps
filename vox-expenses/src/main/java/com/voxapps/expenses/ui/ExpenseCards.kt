package com.voxapps.expenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.domain.llm.ExpenseAmountMismatch
import java.text.DateFormat
import java.util.Date

/** A single expense row: title/vendor, category dot, formatted total, and date. */
@Composable
fun ExpenseCard(expenseWithDetails: ExpenseWithDetails, onClick: () -> Unit) {
    val expense = expenseWithDetails.expense
    val category = expenseWithDetails.category

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (category != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(CategoryColors.fromStored(category.colorArgb))
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (category != null) 10.dp else 0.dp)) {
                Text(
                    text = expense.title?.takeIf { it.isNotBlank() } ?: expense.vendor ?: "—",
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expense.dateTime)) +
                            (category?.let { " · ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = formatAmount(expense.totalAmount, expense.currencyCode),
                style = MaterialTheme.typography.titleMedium,
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
