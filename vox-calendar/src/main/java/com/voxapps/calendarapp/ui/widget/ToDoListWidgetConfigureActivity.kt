package com.voxapps.calendarapp.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import kotlinx.coroutines.launch

/**
 * The single-list to-do widget's placement step: the launcher fires APPWIDGET_CONFIGURE with the
 * fresh appWidgetId, the user picks which [com.voxapps.calendarapp.data.ToDoList] this instance
 * shows, the choice lands in [ToDoWidgetPrefs], and RESULT_OK completes the placement (finishing
 * without choosing cancels it — the launcher then discards the widget, per the appwidget
 * contract). First configuration activity in the suite; the other widgets are configuration-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ToDoListWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancelled is the default until a list is actually chosen.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = (application as CalendarApplication).container
        setContent {
            val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                initialValue = CalendarSettings()
            )
            val lists by container.toDoRepository.lists.collectAsStateWithLifecycle(initialValue = emptyList())
            VoxTheme(
                darkMode = when (settings.themeDarkMode) {
                    CalendarSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                    CalendarSettings.THEME_DARK -> VoxDarkMode.DARK
                    else -> VoxDarkMode.SYSTEM
                },
                colored = settings.themeColored
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(container.languageManager.getString("todo_widget_pick_list")) })
                    }
                ) { padding ->
                    if (lists.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text(container.languageManager.getString("todo_lists_empty"))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(lists, key = { it.id }) { list ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { choose(appWidgetId, list.id) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(list.colorArgb.toInt()).copy(alpha = 0.16f)
                                    )
                                ) {
                                    Text(
                                        text = list.title.ifBlank { container.languageManager.getString("todo_new_list_hint") },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, listId: Long) {
        ToDoWidgetPrefs.setListId(this, appWidgetId, listId)
        lifecycleScope.launch {
            // Draw the freshly-configured instance before handing control back to the launcher.
            val glanceId = GlanceAppWidgetManager(this@ToDoListWidgetConfigureActivity)
                .getGlanceIdBy(appWidgetId)
            ToDoListWidget().update(this@ToDoListWidgetConfigureActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
