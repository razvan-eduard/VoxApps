package com.voxapps.calendarapp

import android.os.Bundle
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarRoot(
                container = container,
                onUnlockRequest = ::promptUnlock
            )
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
}
