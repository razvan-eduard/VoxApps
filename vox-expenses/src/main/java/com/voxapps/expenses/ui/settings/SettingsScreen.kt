package com.voxapps.expenses.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.LocalLanguageManager

private enum class SettingsPage {
    MENU, GENERAL, VOICE, CATEGORIES, EXPENSE_CLEANUP, CURRENCY, NOTIFICATION_CAPTURE, SPENDING_LIMITS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: ExpensesStateManager,
    settingsRepo: ExpensesSettingsRepository,
    exchangeRateRepository: ExchangeRateRepository,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = ExpensesSettings())
    val ui by stateManager.uiState.collectAsStateWithLifecycle()
    val categories = (ui as? ExpensesUiState.Unlocked)?.categories ?: emptyList()
    val expenses = (ui as? ExpensesUiState.Unlocked)?.expenses?.map { it.expense } ?: emptyList()
    val spendingLimits by stateManager.spendingLimits.collectAsStateWithLifecycle(initialValue = emptyList())

    var page by remember { mutableStateOf(SettingsPage.MENU) }

    // System back mirrors the top-bar arrow: subpage -> menu, menu -> expenses list.
    BackHandler { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }

    val title = when (page) {
        SettingsPage.MENU -> languageManager.getString("settings")
        SettingsPage.GENERAL -> languageManager.getString("general")
        SettingsPage.VOICE -> languageManager.getString("voice_settings_title")
        SettingsPage.CATEGORIES -> languageManager.getString("categories_settings_title")
        SettingsPage.EXPENSE_CLEANUP -> languageManager.getString("expense_cleanup_settings_title")
        SettingsPage.CURRENCY -> languageManager.getString("currency_settings_title")
        SettingsPage.NOTIFICATION_CAPTURE -> languageManager.getString("notification_capture_title")
        SettingsPage.SPENDING_LIMITS -> languageManager.getString("spending_limits_title")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == SettingsPage.MENU) onBack() else page = SettingsPage.MENU }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when (page) {
            SettingsPage.MENU -> Column(mod) {
                ListItem(
                    headlineContent = { Text(languageManager.getString("general")) },
                    leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.GENERAL }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("voice_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.VOICE }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("categories_settings_title")) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.CATEGORIES }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("expense_cleanup_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.EXPENSE_CLEANUP }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("currency_settings_title")) },
                    leadingContent = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.CURRENCY }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("notification_capture_title")) },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.NOTIFICATION_CAPTURE }
                )
                ListItem(
                    headlineContent = { Text(languageManager.getString("spending_limits_title")) },
                    leadingContent = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { page = SettingsPage.SPENDING_LIMITS }
                )
            }
            SettingsPage.GENERAL -> GeneralSettingsTab(settings = settings, stateManager = stateManager, modifier = mod)
            SettingsPage.VOICE -> VoiceSettingsTab(
                settings = settings,
                categories = categories,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.CATEGORIES -> CategoriesSettingsTab(
                settings = settings,
                categories = categories,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.EXPENSE_CLEANUP -> ExpenseCleanupSettingsTab(
                settings = settings,
                expenses = expenses,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.CURRENCY -> CurrencySettingsTab(
                settings = settings,
                exchangeRateRepository = exchangeRateRepository,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.NOTIFICATION_CAPTURE -> NotificationCaptureSettingsTab(
                paymentSourcePackages = settings.paymentSourcePackages,
                stateManager = stateManager,
                modifier = mod
            )
            SettingsPage.SPENDING_LIMITS -> SpendingLimitsSettingsTab(
                limits = spendingLimits,
                categories = categories,
                homeCurrency = settings.homeCurrency,
                stateManager = stateManager,
                modifier = mod
            )
        }
    }
}
