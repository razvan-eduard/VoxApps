package com.voxapps.notes

import android.app.Application
import com.voxapps.notes.di.NotesContainer

class NotesApplication : Application() {
    lateinit var container: NotesContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = NotesContainer(this)
    }
}
