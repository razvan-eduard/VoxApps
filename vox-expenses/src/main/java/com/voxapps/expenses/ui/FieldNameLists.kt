package com.voxapps.expenses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.state.ExpensesStateManager

/**
 * The names the bank and vendor fields choose from.
 *
 * [banks] and [vendors] are what this device actually deals with — the banks its records and
 * accounts name, the shops it has paid — because a field you are filling in is nearly always
 * filling in something you have met before. [banksKnown] is the rest of the supplied vocabulary,
 * offered only once someone starts typing: a bank met for the first time is three letters, not
 * seventy-six rows in the way of the four that matter.
 */
data class FieldNameLists(
    val vendors: List<String>,
    val banks: List<String>,
    val banksKnown: List<String>
)

@Composable
fun rememberFieldNameLists(
    stateManager: ExpensesStateManager,
    settings: ExpensesSettings
): FieldNameLists {
    val context = LocalContext.current
    val banksUsed by stateManager.banksInUse.collectAsStateWithLifecycle(initialValue = emptyList())
    val vendorsUsed by stateManager.vendorsInUse.collectAsStateWithLifecycle(initialValue = emptyList())
    val vendors = remember(vendorsUsed, settings.customVendors, settings.disabledVendors) {
        (FieldVocabularies.merge(emptyList(), settings.customVendors, settings.disabledVendors) + vendorsUsed)
            .distinctBy { it.lowercase() }.sorted()
    }
    val banks = remember(banksUsed, settings.customBanks) {
        (banksUsed + settings.customBanks).distinctBy { it.lowercase() }.sorted()
    }
    val banksKnown = remember(settings.customBanks, settings.disabledBanks) {
        FieldVocabularies.merge(
            FieldVocabularies.provided(context).banks,
            settings.customBanks,
            settings.disabledBanks
        )
    }
    return FieldNameLists(vendors = vendors, banks = banks, banksKnown = banksKnown)
}
