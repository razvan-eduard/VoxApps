package com.voxapps.notes.data

import com.voxapps.notes.domain.llm.DuplicateGroup
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

    /** One-shot day-scoped snapshot (Vox Calendar's day-tap summary via Commander IPC). */
    suspend fun notesForDateRange(from: Long, to: Long): List<Note> = noteDao.getForDateRange(from, to)

    // --- Peer-to-peer sync (see :core:datahygiene's SyncMerge and NotesSyncHandler) ---

    suspend fun tombstonesSince(since: Long): List<NoteTombstone> = noteDao.getTombstonesSince(since)

    suspend fun getIdByUid(uid: String): Long? = noteDao.getIdByUid(uid)

    /** Insert-side of a sync merge: preserves [note]'s uid/updatedAt verbatim — unlike [addNote],
     *  which always mints a fresh uid and stamps updatedAt to "now" (correct for a locally *created*
     *  row, wrong for one being replicated from a peer that already has real sync identity). */
    suspend fun insertSyncedNote(note: Note) = noteDao.insert(note.copy(id = 0))

    /** Update-side of a sync merge: [note] must already carry the *local* row's id (resolved via
     *  [getIdByUid] before calling this) — every other field, including updatedAt, comes from the
     *  peer's newer version, since it won the last-write-wins comparison that got us here. */
    suspend fun updateSyncedNote(note: Note) = noteDao.update(note)

    /** Applies an incoming sync tombstone: deletes the local row by uid (a no-op if it's already
     *  gone or was never synced here) via the normal [deleteNoteById] path, so a fresh local
     *  tombstone is written too — letting the deletion propagate transitively to a third device. */
    suspend fun deleteNoteByUid(uid: String) {
        val id = noteDao.getIdByUid(uid) ?: return
        deleteNoteById(id)
    }

    // --- NOTES ---
    suspend fun addNote(title: String?, text: String, categoryId: Long?, createdAt: Long) {
        val clean = text.trim()
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        if (clean.isEmpty() && cleanTitle == null) return
        noteDao.insert(Note(title = cleanTitle, text = clean, createdAt = createdAt, categoryId = categoryId))
    }

    /**
     * Headless voice-note insert: resolves the spoken category name (or the configured default) to a
     * category, saves, and returns the resolved [VoiceCategoryResolver.Resolved] so the caller can
     * toast the category name. Uncategorized when nothing resolves.
     */
    suspend fun addVoiceNote(
        title: String?,
        text: String,
        spokenCategory: String?,
        defaultCategoryId: Long?,
        autoCreate: Boolean,
        createdAt: Long
    ): VoiceCategoryResolver.Resolved {
        val cats = categoryDao.observeAll().first()
        var resolved = VoiceCategoryResolver.resolve(spokenCategory, cats, defaultCategoryId)

        // Unknown spoken category + opt-in → create it (auto-colored) rather than falling back.
        val spoken = spokenCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved.categoryId == null && autoCreate && spoken != null) {
            val id = addCategory(spoken, CategoryPalette.unusedOrRandomColor(cats.map { it.colorArgb }), cats.size, createdAt)
            if (id > 0) resolved = VoiceCategoryResolver.Resolved(id, spoken)
        }

        addNote(title, text, resolved.categoryId, createdAt)
        return resolved
    }

    /** Bumps [Note.updatedAt] to now — never trust a caller-supplied value here, since that's
     *  exactly the field peer-to-peer sync's last-write-wins conflict resolution relies on. */
    suspend fun updateNote(note: Note) = noteDao.update(note.copy(updatedAt = System.currentTimeMillis()))

    /** Update editable fields by id (keeps createdAt). Deletes the note if it ends up empty. */
    suspend fun updateNoteFields(id: Long, title: String?, text: String, categoryId: Long?) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanText = text.trim()
        if (cleanTitle == null && cleanText.isEmpty()) deleteNoteById(id)
        else noteDao.updateFields(id, cleanTitle, cleanText, categoryId, System.currentTimeMillis())
    }

    suspend fun deleteNote(note: Note) {
        noteDao.delete(note)
        noteDao.insertTombstone(NoteTombstone(note.uid, System.currentTimeMillis()))
    }

    suspend fun deleteNoteById(id: Long) {
        val uid = noteDao.getUidById(id)
        noteDao.deleteById(id)
        if (uid != null) noteDao.insertTombstone(NoteTombstone(uid, System.currentTimeMillis()))
    }

    /**
     * Applies a user-approved note-deduplication resolution: for each [DuplicateGroup], deletes every
     * id in `duplicateIds` except `keepId` (in case the LLM redundantly listed the kept note as its
     * own duplicate). Unlike [mergeCategories], this is only ever called after explicit user
     * confirmation in the review UI — see [com.voxapps.notes.domain.llm.NoteDeduplicationRepository].
     */
    suspend fun applyNoteDeduplication(groups: List<DuplicateGroup>) {
        for (group in groups) {
            for (duplicateId in group.duplicateIds) {
                if (duplicateId == group.keepId) continue
                deleteNoteById(duplicateId)
            }
        }
    }

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

    /**
     * Applies an LLM-suggested category merge mapping (old name -> canonical name), e.g. from the
     * Auto-Merge Categories feature. For each entry where both names resolve to an existing category
     * and they differ, reassigns all notes from the old category to the canonical one, then deletes
     * the old category. Case-insensitive name matching, same as [VoiceCategoryResolver]. Entries whose
     * old or canonical name doesn't match any existing category are silently skipped (the LLM may
     * suggest names that no longer exist if categories changed between the request and the reply).
     */
    suspend fun mergeCategories(mapping: Map<String, String>) {
        val cats = categoryDao.observeAll().first()
        for ((oldName, canonicalName) in mapping) {
            if (oldName.equals(canonicalName, ignoreCase = true)) continue
            val old = cats.firstOrNull { it.name.equals(oldName, ignoreCase = true) } ?: continue
            val canonical = cats.firstOrNull { it.name.equals(canonicalName, ignoreCase = true) } ?: continue
            noteDao.reassignCategory(old.id, canonical.id)
            categoryDao.delete(old)
        }
    }
}
