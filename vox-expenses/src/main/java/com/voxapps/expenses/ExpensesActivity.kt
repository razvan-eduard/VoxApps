package com.voxapps.expenses

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.fragment.app.FragmentActivity
import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.security.BiometricGate
import com.voxapps.expenses.ui.ExpensesRoot
import com.voxapps.ipc.VoxIpc

/**
 * Hosts the Vox Expenses UI and the biometric prompt (mirrors vox-notes' NotesActivity).
 * The POST_NOTIFICATIONS runtime request (needed for the spending-limit alert and the "voice save"
 * toast) is now owned by the first-launch onboarding flow (see [com.voxapps.expenses.ui.onboarding.ExpensesOnboardingFlow]),
 * not requested unconditionally here.
 */
class ExpensesActivity : FragmentActivity() {

    private val container: ExpensesContainer by lazy { (application as ExpensesApplication).container }

    // Compose state hoisted here (not just read once in onCreate) so a widget tap while the
    // Activity is already running (onNewIntent, no fresh onCreate/setContent) still reaches
    // ExpensesRoot's composition — see ExpensesRoot's quickAddTrigger/editExpenseTrigger doc comment.
    private val quickAddTrigger = mutableIntStateOf(0)
    private val editExpenseId = mutableLongStateOf(-1L)
    // A separate counter (not just editExpenseId itself): tapping the SAME expense twice in a row
    // must still re-trigger the effect, but two equal Long values wouldn't look like a change.
    private val editExpenseTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        setContent {
            ExpensesRoot(
                container = container,
                onUnlockRequest = ::promptUnlock,
                quickAddTrigger = quickAddTrigger.intValue,
                editExpenseId = editExpenseId.longValue,
                editExpenseTrigger = editExpenseTrigger.intValue
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        // Day-tap-through from another Vox app (e.g. Vox Calendar) — pre-set the existing date filter
        // to just that day rather than restructuring the filter UI itself.
        if (intent?.hasExtra(VoxIpc.EXTRA_SELECTED_DATE) == true) {
            val dayMillis = intent.getLongExtra(VoxIpc.EXTRA_SELECTED_DATE, -1L)
            if (dayMillis >= 0) {
                val day = CalendarDateUtils.millisToLocalDate(dayMillis)
                val from = CalendarDateUtils.startOfDayMillis(day)
                val to = CalendarDateUtils.startOfDayMillis(day.plusDays(1)) - 1
                container.expensesStateManager.setDateFilter(from, to)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true) quickAddTrigger.intValue++
        // EXTRA_EXPENSE_ID is a shared :core:ipc constant (also used for Calendar's day-tap-through
        // deep link into a specific expense) — reused here for the widget's "tap a row to edit" action.
        val id = intent?.getLongExtra(VoxIpc.EXTRA_EXPENSE_ID, -1L) ?: -1L
        if (id >= 0) {
            editExpenseId.longValue = id
            editExpenseTrigger.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock if the session window expired while we were backgrounded (return from Recent Apps).
        container.expensesStateManager.recheckLock()
        // From here the app is unambiguously foreground, so a foreground-service start is allowed —
        // the reliable moment to (re)raise the presence service after an OEM kill left it down, since
        // Application.onCreate can run before the process is TOP and be refused.
        com.voxapps.expenses.receiver.RescanGuard.startIfNeeded(this)
    }

    private fun promptUnlock() {
        if (!BiometricGate.canAuthenticate(this)) {
            // No biometric/credential enrolled: fail open rather than trapping the user out.
            container.expensesStateManager.unlock()
            return
        }
        BiometricGate.authenticate(
            activity = this,
            title = container.languageManager.getString("unlock_title"),
            subtitle = container.languageManager.getString("unlock_subtitle"),
            onSuccess = { container.expensesStateManager.unlock() }
        )
    }

    companion object {
        /** Set by ExpensesWidget's "Add" action — jump straight to a blank new-expense screen. */
        const val EXTRA_QUICK_ADD = "com.voxapps.expenses.EXTRA_QUICK_ADD"
    }
}
