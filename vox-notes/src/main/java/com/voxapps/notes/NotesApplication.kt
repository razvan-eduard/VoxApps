package com.voxapps.notes

import android.app.Application
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler

class NotesApplication : Application() {
    lateinit var container: NotesContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = NotesContainer(this)
        // Re-assert the WorkManager schedule on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored.
        CategoryAutoMergeScheduler.reschedule(this, container.settingsRepository.getSnapshot().scheduledMergeInterval)
    }
}
