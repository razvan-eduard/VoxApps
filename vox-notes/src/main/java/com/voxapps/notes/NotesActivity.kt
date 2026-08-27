package com.voxapps.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

    // Compose state hoisted here (not just read once in onCreate) so a widget tap while the
    // Activity is already running (onNewIntent, no fresh onCreate/setContent) still reaches
    // NotesRoot's composition — see NotesRoot's quickAddTrigger/editNoteTrigger doc comment.
    private val quickAddTrigger = mutableIntStateOf(0)
    private val editNoteId = mutableLongStateOf(-1L)
    // A separate counter (not just editNoteId itself): tapping the SAME note twice in a row must
    // still re-trigger the effect, but two equal Long values wouldn't look like a change.
    private val editNoteTrigger = mutableIntStateOf(0)

    override fun onPause() {
        // A voice note must not keep talking once the app leaves the screen.
        com.voxapps.attachments.VoiceNotePlayer.stop()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        setContent {
            NotesRoot(
                container = container,
                onUnlockRequest = ::promptUnlock,
                quickAddTrigger = quickAddTrigger.intValue,
                editNoteId = editNoteId.longValue,
                editNoteTrigger = editNoteTrigger.intValue
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
                container.notesStateManager.setDateFilter(from, to)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true) quickAddTrigger.intValue++
        val id = intent?.getLongExtra(EXTRA_EDIT_NOTE_ID, -1L) ?: -1L
        if (id >= 0) {
            editNoteId.longValue = id
            editNoteTrigger.intValue++
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

    companion object {
        /** Set by NotesWidget's "Add" action — jump straight to a blank new-note editor. */
        const val EXTRA_QUICK_ADD = "com.voxapps.notes.EXTRA_QUICK_ADD"
        /** Set by NotesWidget's note rows — jump straight to that note's inline editor. Also used
         *  cross-app by Vox Calendar's day-summary sheet, hence the [VoxIpc] alias. */
        const val EXTRA_EDIT_NOTE_ID = VoxIpc.EXTRA_EDIT_NOTE_ID
    }
}
