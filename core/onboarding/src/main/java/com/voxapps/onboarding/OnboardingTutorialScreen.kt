package com.voxapps.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One step inside a page: the thing being pointed at, and what it does. */
data class TutorialStep(val element: String, val description: String)

/** One page of a tutorial. Prose, steps, or both — a page with neither is not shown. */
data class TutorialPage(
    val title: String,
    val paragraphs: List<String> = emptyList(),
    val steps: List<TutorialStep> = emptyList()
) {
    val isEmpty: Boolean get() = paragraphs.isEmpty() && steps.isEmpty()
}

/**
 * The tour an app gives before it asks for anything.
 *
 * Shared because the order matters and was only right in one app: explain, then ask. A permission
 * dialog that arrives before a person knows what the app is asking on behalf of is a dialog they
 * dismiss, and an app cannot ask twice. So this screen comes first everywhere and the permissions
 * screen comes last.
 *
 * Content is the caller's — pages come from a per-language asset in one app and from its
 * translations in another — because what a tutorial says is the one part no two apps share.
 *
 * Skippable from every page, and never a wall: a tour somebody cannot leave is a tour they resent
 * rather than read, and everything it covers is reachable from settings anyway.
 */
@Composable
fun OnboardingTutorialScreen(
    pages: List<TutorialPage>,
    skipLabel: String,
    backLabel: String,
    nextLabel: String,
    finishLabel: String,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val shown = pages.filterNot { it.isEmpty }
    // Nothing to say is not a page to sit on: an app whose tutorial asset failed to load should
    // reach the permissions screen rather than an empty pager.
    if (shown.isEmpty()) {
        onSkip()
        return
    }

    val pagerState = rememberPagerState(pageCount = { shown.size })
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1f) / shown.size },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { index ->
                val page = shown[index]
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    page.paragraphs.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                    }
                    // A step names a control and says what it does, so it is carded: prose is read
                    // and a step is looked up later.
                    page.steps.forEach { step ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(step.element, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    step.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Skip stays put rather than turning into Back on later pages: a control that
                // changes meaning under the same finger is one people stop trusting.
                TextButton(onClick = onSkip) { Text(skipLabel) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pagerState.currentPage > 0) {
                        TextButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }) { Text(backLabel) }
                    }
                    val last = pagerState.currentPage == shown.lastIndex
                    Button(onClick = {
                        if (last) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) { Text(if (last) finishLabel else nextLabel) }
                }
            }
        }
    }
}
