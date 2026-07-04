package com.voxcommander.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxcommander.app.domain.localization.TutorialManager
import kotlinx.coroutines.launch

@Composable
fun TutorialScreen(
    tutorialManager: TutorialManager,
    langCode: String,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val sections = remember(langCode) { tutorialManager.getSections(langCode) }
    val pagerState = rememberPagerState(pageCount = { sections.size })
    val scope = rememberCoroutineScope()

    if (sections.isEmpty()) {
        onSkip()
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1f) / sections.size },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                val section = sections[page]
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // Paragraphs (welcome + final sections)
                    section.paragraphs.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Steps (screen-specific sections)
                    section.steps.forEach { step ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Text(
                                    text = step.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }

                Text(
                    text = "${pagerState.currentPage + 1} / ${sections.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                if (pagerState.currentPage < sections.size - 1) {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }
                    ) {
                        Text("Next")
                    }
                } else {
                    Button(onClick = onFinish) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
