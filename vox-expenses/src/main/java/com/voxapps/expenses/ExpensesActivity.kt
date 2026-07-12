package com.voxapps.expenses

import android.os.Bundle
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Day-tap-through from another Vox app (e.g. Vox Calendar) — pre-set the existing date filter
        // to just that day rather than restructuring the filter UI itself.
        if (intent.hasExtra(VoxIpc.EXTRA_SELECTED_DATE)) {
            val dayMillis = intent.getLongExtra(VoxIpc.EXTRA_SELECTED_DATE, -1L)
            if (dayMillis >= 0) {
                val day = CalendarDateUtils.millisToLocalDate(dayMillis)
                val from = CalendarDateUtils.startOfDayMillis(day)
                val to = CalendarDateUtils.startOfDayMillis(day.plusDays(1)) - 1
                container.expensesStateManager.setDateFilter(from, to)
            }
        }
        setContent {
            ExpensesRoot(
                container = container,
                onUnlockRequest = ::promptUnlock
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock if the session window expired while we were backgrounded (return from Recent Apps).
        container.expensesStateManager.recheckLock()
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
}
