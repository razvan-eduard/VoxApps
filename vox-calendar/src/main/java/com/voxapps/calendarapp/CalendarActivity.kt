package com.voxapps.calendarapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.fragment.app.FragmentActivity
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.security.BiometricGate
import com.voxapps.calendarapp.ui.CalendarRoot

/**
 * Hosts the Vox Calendar UI and the biometric prompt (mirrors vox-expenses' ExpensesActivity).
 * The POST_NOTIFICATIONS runtime request (needed for a headless voice/LLM-created event's toast) is
 * now owned by the first-launch onboarding flow (see
 * [com.voxapps.calendarapp.ui.onboarding.CalendarOnboardingFlow]), not requested unconditionally here.
 */
class CalendarActivity : FragmentActivity() {

    private val container: CalendarContainer by lazy { (application as CalendarApplication).container }

    // Compose state hoisted here (not just read once in onCreate) so a widget tap while the
    // Activity is already running (onNewIntent, no fresh onCreate/setContent) still reaches
    // CalendarRoot's composition — see CalendarRoot's quickAddTrigger/editEntryTrigger doc comment.
    private val quickAddTrigger = mutableIntStateOf(0)
    private val editEntryId = mutableLongStateOf(-1L)
    // A separate counter (not just editEntryId itself): tapping the SAME record twice in a row
    // must still re-trigger the effect, but two equal Long values wouldn't look like a change.
    private val editEntryTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        setContent {
            CalendarRoot(
                container = container,
                onUnlockRequest = ::promptUnlock,
                quickAddTrigger = quickAddTrigger.intValue,
                editEntryId = editEntryId.longValue,
                editEntryTrigger = editEntryTrigger.intValue
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true) quickAddTrigger.intValue++
        val id = intent?.getLongExtra(EXTRA_EDIT_ENTRY_ID, -1L) ?: -1L
        if (id >= 0) {
            editEntryId.longValue = id
            editEntryTrigger.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock if the session window expired while we were backgrounded (return from Recent Apps).
        container.calendarStateManager.recheckLock()
    }

    private fun promptUnlock() {
        if (!BiometricGate.canAuthenticate(this)) {
            // No biometric/credential enrolled: fail open rather than trapping the user out.
            container.calendarStateManager.unlock()
            return
        }
        BiometricGate.authenticate(
            activity = this,
            title = container.languageManager.getString("unlock_title"),
            subtitle = container.languageManager.getString("unlock_subtitle"),
            onSuccess = { container.calendarStateManager.unlock() }
        )
    }

    companion object {
        /** Set by CalendarWidget's "Add" action — jump straight to a blank new-entry screen. */
        const val EXTRA_QUICK_ADD = "com.voxapps.calendarapp.EXTRA_QUICK_ADD"
        /** Set by CalendarWidget's record rows — jump straight to that entry's edit screen. */
        const val EXTRA_EDIT_ENTRY_ID = "com.voxapps.calendarapp.EXTRA_EDIT_ENTRY_ID"
    }
}
