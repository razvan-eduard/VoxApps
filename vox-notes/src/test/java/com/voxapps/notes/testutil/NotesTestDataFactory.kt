package com.voxapps.notes.testutil

import com.voxapps.notes.data.Category
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.data.preferences.NotesSettings

/**
 * Object Mother for VoxNotes tests (mirrors vox-commander's TestDataFactory). Sensible defaults so
 * tests stay readable and resilient to data-shape changes.
 */
object NotesTestDataFactory {

    fun category(
        id: Long = 1,
        name: String = "Shopping",
        colorArgb: Long = 0xFFAB47BCL,
        position: Int = 0,
        createdAt: Long = 1_000L
    ) = Category(id = id, name = name, colorArgb = colorArgb, position = position, createdAt = createdAt)

    fun note(
        id: Long = 1,
        title: String? = null,
        text: String = "buy milk",
        createdAt: Long = 1_000L,
        categoryId: Long? = null
    ) = Note(id = id, title = title, text = text, createdAt = createdAt, categoryId = categoryId)

    fun noteWithCategory(
        note: Note = note(),
        category: Category? = null
    ) = NoteWithCategory(note = note, category = category)

    fun settings(
        isBiometricRequired: Boolean = false,
        sessionTimeoutMinutes: Int = NotesSettings.TIMEOUT_30M
    ) = NotesSettings(isBiometricRequired = isBiometricRequired, sessionTimeoutMinutes = sessionTimeoutMinutes)
}
