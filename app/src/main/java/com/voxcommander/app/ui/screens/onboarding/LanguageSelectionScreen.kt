package com.voxcommander.app.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxcommander.app.domain.localization.LanguageManager

@Composable
fun LanguageSelectionScreen(
    languageManager: LanguageManager,
    onLanguageSelected: (String) -> Unit
) {
    val availableLanguages = remember { languageManager.getAvailableLanguages() }
    val languageNames = mapOf(
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "ro" to "Română"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "VoxCommander",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Select your language / Wählen Sie Ihre Sprache / Sélectionnez votre langue / Selectați limba",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(availableLanguages, key = { it }) { langCode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .clickable { onLanguageSelected(langCode) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = languageNames[langCode] ?: langCode.uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 20.dp, horizontal = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
