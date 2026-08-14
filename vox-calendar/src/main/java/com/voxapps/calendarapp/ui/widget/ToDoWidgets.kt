package com.voxapps.calendarapp.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.voxapps.calendarapp.CalendarActivity
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.ToDoItem
import com.voxapps.calendarapp.data.ToDoList
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.todo.sortedByDateKeepingUndatedInPlace
import com.voxapps.widget.VoxWidgetScaffold
import com.voxapps.widget.WidgetDayFormats
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The two to-do home-screen widgets, sharing rows and deep-links:
 *
 *  - [ToDoListsWidget]: every list as a card, rendered like the in-app view faces (list-colored
 *    card, node bullet + item-colored pill per row) as far as Glance's RemoteViews translation
 *    allows (no glows, no star shapes, no animations — same limits CalendarWidget already
 *    documents for its to-do-flavored rows). Tapping a card opens the app with that list flipped
 *    to its edit face; tapping a row opens that item's edit dialog; the bottom "+" creates a new
 *    list straight in edit mode, mirroring the in-app FAB.
 *
 *  - [ToDoListWidget]: one user-chosen list (picked in [ToDoListWidgetConfigureActivity] when the
 *    widget is placed), same rows WITHOUT the card dressing, and no add button — items are added
 *    inside the list's edit face, which its title opens. The chosen id is per-appWidgetId (see
 *    [ToDoWidgetPrefs]).
 *
 * Every dynamic value is collected INSIDE the composition — see ExpensesWidget's doc for why data
 * read into provideGlance locals freezes for the session's lifetime.
 */
class ToDoListsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CalendarApplication).container

        provideContent {
            val uiState by container.calendarStateManager.uiState.collectAsState()
            val lists by container.toDoRepository.lists.collectAsState(initial = emptyList())
            val itemsByList by container.toDoRepository.allItems.collectAsState(initial = emptyMap())
            val languageManager = container.languageManager

            GlanceTheme {
                VoxWidgetScaffold(
                    title = languageManager.getString("todo_lists_title"),
                    openAppAction = actionStartActivity(
                        Intent(context, CalendarActivity::class.java).apply {
                            putExtra(CalendarActivity.EXTRA_OPEN_TODO_LISTS, true)
                        }
                    ),
                    locked = uiState is CalendarUiState.Locked,
                    lockedText = languageManager.getString("locked_title"),
                    scan = null,
                    addButtonText = languageManager.getString("todo_widget_add_button"),
                    addAction = actionStartActivity(
                        Intent(context, CalendarActivity::class.java).apply {
                            putExtra(CalendarActivity.EXTRA_TODO_QUICK_ADD, true)
                        }
                    )
                ) {
                    if (lists.isEmpty()) {
                        Text(
                            text = languageManager.getString("todo_lists_empty"),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                        )
                    } else {
                        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(lists, itemId = { it.id }) { list ->
                                val listEditAction = actionStartActivity(
                                    Intent(context, CalendarActivity::class.java).apply {
                                        putExtra(CalendarActivity.EXTRA_OPEN_TODO_LIST_ID, list.id)
                                    }
                                )
                                Column(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .cornerRadius(12.dp)
                                        .background(Color(list.colorArgb.toInt()).copy(alpha = 0.16f))
                                        .padding(10.dp)
                                        .clickable(listEditAction)
                                ) {
                                    Text(
                                        text = list.title.ifBlank { languageManager.getString("todo_new_list_hint") },
                                        maxLines = 1,
                                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface)
                                    )
                                    Spacer(modifier = GlanceModifier.height(4.dp))
                                    val items = itemsByList[list.id].orEmpty()
                                    if (items.isEmpty()) {
                                        Text(
                                            text = languageManager.getString("todo_list_empty"),
                                            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
                                        )
                                    } else {
                                        ToDoItemRows(items, context, languageManager.getString("todo_new_item_hint"))
                                    }
                                }
                                Spacer(modifier = GlanceModifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

class ToDoListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CalendarApplication).container
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val configuredListId = ToDoWidgetPrefs.getListId(context, appWidgetId)

        provideContent {
            val uiState by container.calendarStateManager.uiState.collectAsState()
            val lists by container.toDoRepository.lists.collectAsState(initial = emptyList())
            val itemsByList by container.toDoRepository.allItems.collectAsState(initial = emptyMap())
            val languageManager = container.languageManager
            val list = lists.firstOrNull { it.id == configuredListId }

            GlanceTheme {
                VoxWidgetScaffold(
                    title = list?.title?.ifBlank { languageManager.getString("todo_new_list_hint") }
                        ?: languageManager.getString("todo_lists_title"),
                    openAppAction = actionStartActivity(
                        Intent(context, CalendarActivity::class.java).apply {
                            if (list != null) putExtra(CalendarActivity.EXTRA_OPEN_TODO_LIST_ID, list.id)
                            else putExtra(CalendarActivity.EXTRA_OPEN_TODO_LISTS, true)
                        }
                    ),
                    locked = uiState is CalendarUiState.Locked,
                    lockedText = languageManager.getString("locked_title"),
                    scan = null
                ) {
                    when {
                        list == null -> Text(
                            text = languageManager.getString("todo_widget_list_missing"),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                        )
                        else -> {
                            val items = itemsByList[list.id].orEmpty()
                            if (items.isEmpty()) {
                                Text(
                                    text = languageManager.getString("todo_list_empty"),
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                                )
                            } else {
                                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                                    items(sortedByDateKeepingUndatedInPlace(items), itemId = { it.id }) { item ->
                                        Column {
                                            ToDoItemRow(item, context, languageManager.getString("todo_new_item_hint"))
                                            Spacer(modifier = GlanceModifier.height(2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The non-lazy rows a list card holds — used by the all-lists widget, where each CARD is the lazy
 *  item (mirrors the in-app screen: one card per list, rows inside). */
@Composable
private fun ToDoItemRows(items: List<ToDoItem>, context: Context, blankItemHint: String) {
    sortedByDateKeepingUndatedInPlace(items).forEach { item ->
        ToDoItemRow(item, context, blankItemHint)
        Spacer(modifier = GlanceModifier.height(2.dp))
    }
}

/** One node row: item-colored bullet + pill (bold + check when done) + due label — the in-app
 *  timeline row, minus what RemoteViews can't draw (glow, star shape, connectors). Tapping opens
 *  the item's edit dialog via the entry-edit deep-link CalendarWidget's rows already use. */
@Composable
private fun ToDoItemRow(item: ToDoItem, context: Context, blankItemHint: String) {
    val editIntent = Intent(context, CalendarActivity::class.java).apply {
        putExtra(CalendarActivity.EXTRA_EDIT_ENTRY_ID, item.id)
    }
    val itemColor = Color(item.colorArgb.toInt())
    val textColor = if (itemColor.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(actionStartActivity(editIntent)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Box(modifier = GlanceModifier.size(10.dp).cornerRadius(5.dp).background(itemColor)) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        // Pill styling applied directly on Text's own modifier — see CalendarWidget's to-do rows
        // for why a wrapping Box renders the pill but loses the text in Glance's translation.
        Text(
            text = buildString {
                append(item.text.ifBlank { blankItemHint })
                if (item.done) append(" ✓")
            },
            maxLines = 1,
            style = TextStyle(
                fontSize = 13.sp,
                color = ColorProvider(textColor),
                fontWeight = if (item.done) FontWeight.Bold else FontWeight.Normal
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .cornerRadius(50.dp)
                .background(itemColor)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
        val due = item.dueMillis
        if (due != null) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            val dueDate = Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalDate()
            Text(
                text = if (dueDate == LocalDate.now()) {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(due))
                } else {
                    WidgetDayFormats.short(dueDate, Locale.getDefault())
                },
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
            )
        }
    }
}

class ToDoListsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToDoListsWidget()
}

class ToDoListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToDoListWidget()
}

/** Which [ToDoList] a [ToDoListWidget] instance shows — keyed per appWidgetId, written once by
 *  [ToDoListWidgetConfigureActivity] when the widget is placed. Plain SharedPreferences: one long
 *  per widget instance is beneath DataStore's ceremony, and it's read on Glance's own thread. */
object ToDoWidgetPrefs {
    private const val PREFS = "todo_widget_prefs"

    fun getListId(context: Context, appWidgetId: Int): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("list_$appWidgetId", -1L)

    fun setListId(context: Context, appWidgetId: Int, listId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("list_$appWidgetId", listId).apply()
    }
}
