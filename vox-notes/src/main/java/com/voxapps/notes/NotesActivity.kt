package com.voxapps.notes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.ipc.VoxIpc
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.security.BiometricGate
import com.voxapps.notes.ui.NotesRoot

/**
 * Hosts the VoxNotes UI and the biometric prompt. Cross-app commands (create/read) are handled
 * headlessly by [com.voxapps.notes.receiver.VoxCommandReceiver], not here.
 */
class NotesActivity : FragmentActivity() {

    private val container: NotesContainer by lazy { (application as NotesApplication).container }

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
                container.notesStateManager.setDateFilter(from, to)
            }
        }
        setContent {
            NotesRoot(
                container = container,
                onUnlockRequest = ::promptUnlock
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock if the session window expired while we were backgrounded (return from Recent Apps).
        container.notesStateManager.recheckLock()
    }

    private fun promptUnlock() {
        if (!BiometricGate.canAuthenticate(this)) {
            // No biometric/credential enrolled: fail open rather than trapping the user out.
            container.notesStateManager.unlock()
            return
        }
        BiometricGate.authenticate(
            activity = this,
            title = container.languageManager.getString("unlock_title"),
            subtitle = container.languageManager.getString("unlock_subtitle"),
            onSuccess = { container.notesStateManager.unlock() }
        )
    }
}
