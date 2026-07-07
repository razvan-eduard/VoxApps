package com.voxapps.notes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single write point over the Room DAOs (mirrors how vox-commander's AppStateManager delegates
 * persistence to SettingsRepository). NotesStateManager observes [notes] / [categories] and calls
 * the suspend writers.
 */
class NotesRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao
) {
    val notes: Flow<List<Note>> = noteDao.observeAll()
    val notesWithCategory: Flow<List<NoteWithCategory>> = noteDao.observeNotesWithCategory()
    val categories: Flow<List<Category>> = categoryDao.observeAll()

    /** One-shot snapshot for the headless read path (Commander IPC). */
    suspend fun notesSnapshot(): List<Note> = noteDao.observeAll().first()

    // --- NOTES ---
    suspend fun addNote(title: String?, text: String, categoryId: Long?, createdAt: Long) {
        val clean = text.trim()
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        if (clean.isEmpty() && cleanTitle == null) return
        noteDao.insert(Note(title = cleanTitle, text = clean, createdAt = createdAt, categoryId = categoryId))
    }

    suspend fun updateNote(note: Note) = noteDao.update(note)

    suspend fun deleteNote(note: Note) = noteDao.delete(note)

    // --- CATEGORIES ---
    suspend fun addCategory(name: String, colorArgb: Long, position: Int, createdAt: Long): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return categoryDao.insert(
            Category(name = clean, colorArgb = colorArgb, position = position, createdAt = createdAt)
        )
    }

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    /** Deleting a category leaves its notes intact — they become uncategorized. */
    suspend fun deleteCategory(category: Category) {
        noteDao.clearCategory(category.id)
        categoryDao.delete(category)
    }
}
