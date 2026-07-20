package com.voxapps.commander.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import com.voxapps.commander.MainActivity
import com.voxapps.commander.R

/**
 * Independent "tap to speak" home-screen widget — not tied to any satellite's data, just a direct
 * trigger into the voice-capture flow MainScreen's own mic button already uses (see
 * [com.voxapps.commander.MainActivity.EXTRA_AUTO_START_LISTENING] and the matching LaunchedEffect
 * in MainScreen). Unlike the per-satellite widgets, this has no data to read, so no repository/
 * uiState wiring or [androidx.glance.appwidget.updateAll] refresh hook is needed at all.
 */
class SpeakWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                SpeakWidgetContent()
            }
        }
    }
}

@Composable
private fun SpeakWidgetContent() {
    val context = LocalContext.current
    val speakIntent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_AUTO_START_LISTENING, true)
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(actionStartActivity(speakIntent)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_mic),
            contentDescription = context.getString(R.string.speak_widget_label),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(28.dp)
        )
    }
}
