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
import com.voxapps.expenses.data.ExpenseWithDetails
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
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expense.dateTime)) +
                        (category?.let { " · ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
