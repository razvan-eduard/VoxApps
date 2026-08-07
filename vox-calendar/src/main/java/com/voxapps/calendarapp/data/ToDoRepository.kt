package com.voxapps.calendarapp.data

import com.voxapps.design.color.VoxColorPalette
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Single write point over to-do lists/items (mirrors [CalendarRepository]'s own role for
 * `calendar_entries`). A to-do item IS a [CalendarEntry] row with [CalendarEntry.listId] set — there
 * is no separate `todo_items` table (retired in DB migration 9->10, see [ToDoItem]'s doc comment) and
 * no shadow-entry sync glue: creating/updating an item just creates/updates its own row directly.
 * Reminder-affecting writes are delegated to [calendarRepository] (via [CalendarRepository
 * .updateEntry]) so scheduling logic stays in exactly one place; plain metadata writes (text, color,
 * importance, comments, position, done) go straight through [entryDao] since they never touch
 * reminders and must NOT risk wiping them (see [setItemDueDate]'s doc comment).
 */
class ToDoRepository(
    private val listDao: ToDoListDao,
    private val entryDao: CalendarEntryDao,
    private val calendarRepository: CalendarRepository
) {
    val lists: Flow<List<ToDoList>> = listDao.observeAll().distinctUntilChanged()

    fun itemsForList(listId: Long): Flow<List<ToDoItem>> =
        entryDao.observeForList(listId).map { entries -> entries.map { it.toToDoItem() } }
            .distinctUntilChanged()

    suspend fun createList(title: String, layerId: Long): Long {
        val now = System.currentTimeMillis()
        val existingColors = lists.first().map { it.colorArgb }
        return listDao.insert(
            ToDoList(
                uid = UUID.randomUUID().toString(),
                title = title.trim(),
                colorArgb = VoxColorPalette.unusedOrRandomColor(existingColors),
                layerId = layerId,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun renameList(list: ToDoList, title: String) {
        listDao.update(list.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateListColor(list: ToDoList, colorArgb: Long) {
        listDao.update(list.copy(colorArgb = colorArgb, updatedAt = System.currentTimeMillis()))
    }

    /** Deletes every item's underlying [CalendarEntry] row via [calendarRepository] (so reminders,
     *  tombstones, and attachments are cleaned up the same way any entry delete is), then the list
     *  itself. */
    suspend fun deleteList(list: ToDoList) {
        val items = entryDao.getForList(list.id)
        for (item in items) calendarRepository.deleteEntry(item)
        listDao.delete(list)
    }

    /** [atPosition] inserts before the existing item currently at that index (0 = start, size = end);
     *  `null` appends at the end — computed from the highest existing [ToDoItem.position] rather than
     *  `existing.size`, since a gap left by an interrupted/older delete path shouldn't let a new item
     *  land before the current last item just because the count is lower than the max position in use.
     *  Every other item at or after the insertion point shifts down one. */
    suspend fun addItem(listId: Long, text: String, atPosition: Int? = null): Long {
        val now = System.currentTimeMillis()
        val list = listDao.getById(listId) ?: error("No such to-do list: $listId")
        val existing = entryDao.getForList(listId)
        val insertAt = atPosition?.coerceIn(0, existing.size) ?: ((existing.maxOfOrNull { it.position } ?: -1) + 1)
        for (item in existing) {
            if (item.position >= insertAt) entryDao.update(item.copy(position = item.position + 1, updatedAt = now))
        }
        return entryDao.insert(
            CalendarEntry(
                uid = UUID.randomUUID().toString(),
                type = CalendarEntryType.TASK,
                title = text.trim(),
                startMillis = null,
                allDay = false,
                completed = false,
                layerId = list.layerId,
                listId = listId,
                position = insertAt,
                colorArgb = VoxColorPalette.unusedOrRandomColor(existing.mapNotNull { it.colorArgb }),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateItemText(item: ToDoItem, text: String) {
        val entry = entryDao.getById(item.id) ?: return
        entryDao.update(entry.copy(title = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateItemColor(item: ToDoItem, colorArgb: Long) {
        val entry = entryDao.getById(item.id) ?: return
        entryDao.update(entry.copy(colorArgb = colorArgb, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateItemImportant(item: ToDoItem, isImportant: Boolean) {
        val entry = entryDao.getById(item.id) ?: return
        entryDao.update(entry.copy(isImportant = isImportant, updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleDone(item: ToDoItem) {
        val entry = entryDao.getById(item.id) ?: return
        entryDao.update(entry.copy(completed = !entry.completed, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateItemComments(item: ToDoItem, comments: String?) {
        val entry = entryDao.getById(item.id) ?: return
        entryDao.update(entry.copy(comments = comments?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = System.currentTimeMillis()))
    }

    /** Renormalizes the remaining items' [ToDoItem.position] to a contiguous 0-based sequence after
     *  the delete — otherwise a gap is left where [item] used to sit, which throws off any later
     *  [addItem] append (its "end" is the highest position in use, not the item count) and any code
     *  that assumes positions and list size stay in lockstep. */
    suspend fun deleteItem(item: ToDoItem) {
        val entry = entryDao.getById(item.id) ?: return
        calendarRepository.deleteEntry(entry)
        val now = System.currentTimeMillis()
        entryDao.getForList(item.listId).forEachIndexed { index, remaining ->
            if (remaining.position != index) entryDao.update(remaining.copy(position = index, updatedAt = now))
        }
    }

    /** [orderedItems] is the full item list for one [ToDoList] in its new desired order; persists
     *  0-based [ToDoItem.position] for each. */
    suspend fun reorderItems(orderedItems: List<ToDoItem>) {
        orderedItems.forEachIndexed { index, item ->
            if (item.position != index) {
                val entry = entryDao.getById(item.id) ?: return@forEachIndexed
                entryDao.update(entry.copy(position = index, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * Sets/clears [ToDoItem.dueMillis] directly on the item's own row (`null` clears it). Goes through
     * [calendarRepository] (not a raw DAO update) so [CalendarRepository.updateEntry]'s reminder
     * scheduling reacts to the date change — but re-supplies the item's CURRENT reminder offsets
     * rather than an empty list, since [CalendarRepository.updateEntry] always replaces reminders
     * wholesale from whatever offsets list is passed in; passing `emptyList()` here would silently
     * wipe any reminder the user already set on this item.
     */
    suspend fun setItemDueDate(item: ToDoItem, dueMillis: Long?, list: ToDoList) {
        val entry = entryDao.getById(item.id) ?: return
        val currentOffsets = calendarRepository.getRemindersForEntry(item.id).map { it.offsetMinutesBefore }
        calendarRepository.updateEntry(entry.copy(startMillis = dueMillis), emptyList(), currentOffsets)
    }

    suspend fun getReminderOffsetsForItem(item: ToDoItem): List<Int> =
        calendarRepository.getRemindersForEntry(item.id).map { it.offsetMinutesBefore }

    suspend fun setItemReminders(item: ToDoItem, offsetsMinutes: List<Int>) {
        val entry = entryDao.getById(item.id) ?: return
        calendarRepository.updateEntry(entry, emptyList(), offsetsMinutes)
    }

    /**
     * Headless item insert from a parsed voice/LLM/scan result: resolves the spoken/suggested list
     * name (exact match, then fuzzy match via the shared `:core:textmatch` resolver — same algorithm
     * [CalendarRepository.addParsedEntry] uses for layer resolution) or falls back to the
     * most-recently-created list ([ToDoListDao.observeAll] is `ORDER BY createdAt DESC`, so
     * `lists.first()` is exactly that) — auto-creating a single bootstrap list if none exist yet at
     * all (a one-time act, not a repeated fuzzy-miss action, so safe to do unconditionally rather than
     * gating behind a setting). Creates the item, and — only if a date was actually given — attaches
     * it via [setItemDueDate].
     */
    suspend fun addParsedItem(spokenListName: String?, text: String, dueMillis: Long?, defaultLayerId: Long?): Long {
        val existingLists = lists.first()
        val list = if (existingLists.isEmpty()) {
            val layers = calendarRepository.layersSnapshot()
            val layerId = defaultLayerId ?: layers.firstOrNull { it.isDefault }?.id ?: layers.firstOrNull()?.id
                ?: error("No calendar layer exists to assign this to-do list to")
            val title = spokenListName?.trim()?.takeIf { it.isNotEmpty() } ?: "Tasks"
            val newListId = createList(title, layerId)
            listDao.getById(newListId) ?: error("Failed to create bootstrap to-do list")
        } else {
            val resolved = FuzzyNameMatcher.resolve(
                spokenName = spokenListName,
                candidates = existingLists.map { FuzzyNameMatcher.Candidate(it.id, it.title) },
                defaultId = existingLists.first().id
            )
            existingLists.firstOrNull { it.id == resolved.id } ?: existingLists.first()
        }
        val itemId = addItem(list.id, text)
        if (dueMillis != null) {
            val item = entryDao.getForList(list.id).first { it.id == itemId }.toToDoItem()
            setItemDueDate(item, dueMillis, list)
        }
        return itemId
    }
}

/** Public so callers outside [ToDoRepository] (e.g. [com.voxapps.calendarapp.ui.CalendarRoot], routing
 *  a tapped calendar-grid entry to the to-do edit UI when it's to-do-flavored) can map a raw
 *  [CalendarEntry] to the view-model the to-do UI expects, without duplicating the mapping. */
fun CalendarEntry.toToDoItem(): ToDoItem = ToDoItem(
    id = id,
    listId = listId ?: error("CalendarEntry $id has no listId — not a to-do item"),
    text = title,
    position = position,
    colorArgb = colorArgb ?: 0xFF9E9E9EL,
    dueMillis = startMillis,
    done = completed,
    isImportant = isImportant,
    comments = comments,
    createdAt = createdAt,
    updatedAt = updatedAt
)
