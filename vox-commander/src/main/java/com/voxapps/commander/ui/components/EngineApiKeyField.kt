package com.voxapps.commander.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager

/**
 * The credential field for [engineKey], shown beside the engine it belongs to.
 *
 * Renders nothing at all unless the engine declares `requires_api_key`, so a caller places it under
 * whichever engine is *selected* and the field appears only when that engine actually needs one.
 * That is the point: a key asked for where the choice is made explains itself, whereas a card of
 * every service's key sitting above the page asks the user to work out which one applies to them.
 *
 * There is one field per engine and one slot per engine behind it, so the same engine's key entered
 * from two screens is the same value — the search settings show this for the OpenAI credential their
 * shared-key providers borrow, and it is the same credential the intent engine uses.
 */
@Composable
fun EngineApiKeyField(
    engineKey: String,
    appStateManager: AppStateManager,
    languageManager: LanguageManager,
    modifier: Modifier = Modifier,
    onKeyChanged: (String) -> Unit = {}
) {
    if (!RemoteModelRegistry.hasCapability(engineKey, "requires_api_key")) return

    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val stored = uiState.credentials.forEngine(engineKey) ?: ""

    var value by remember(engineKey) { mutableStateOf(stored) }
    var isFocused by remember(engineKey) { mutableStateOf(false) }

    /*
     * The credential is written when the field is *finished with* — focus lost, or the screen left —
     * never on each keystroke.
     *
     * Writing per keystroke stores a partial credential dozens of times, tells every observer about
     * each one, and pushes half a key to the search providers on the way past. It also truncates:
     * each write returns asynchronously through the store's change listener, and the arriving value
     * re-initialises the field, so typing faster than that round trip resets the text to a stale
     * prefix and silently discards the rest — a key that looks entered and is stored short.
     *
     * Read through [rememberUpdatedState] so the dispose path commits what is on screen now, not
     * what was there when the effect was created.
     */
    val latestValue by rememberUpdatedState(value)
    val latestStored by rememberUpdatedState(stored)
    val commit = {
        if (latestValue != latestStored) {
            appStateManager.setEngineApiKey(engineKey, latestValue.ifBlank { null })
            onKeyChanged(latestValue)
        }
    }

    // Leaving the screen counts as finishing: a key typed and then navigated away from is kept.
    DisposableEffect(engineKey) { onDispose { commit() } }

    // An external change (an import, a restore) replaces what is shown — but never mid-edit.
    LaunchedEffect(stored, isFocused) {
        if (!isFocused && stored != value) value = stored
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Where to get the key, when the engine says. A localized sentence if it declares one (the host
    // inside it becomes the link), otherwise just the host as a link — so an engine can be useful
    // here with a single line of JSON and no translation work.
    val helpKey = RemoteModelRegistry.declaredApiKeyHelpKey(engineKey)
    val keyUrl = RemoteModelRegistry.declaredApiKeyUrl(engineKey)
    val helpText = helpKey?.let { languageManager.getString(it) }?.takeIf { it.isNotBlank() && it != helpKey }
    val host = keyUrl?.toUri()?.host

    if (host != null && keyUrl != null) {
        val uriHandler = LocalUriHandler.current
        val annotated = remember(helpText, host, keyUrl) {
            buildAnnotatedString {
                val sentence = helpText ?: host
                val linkStart = sentence.indexOf(host)
                if (linkStart >= 0) {
                    append(sentence.substring(0, linkStart))
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        withAnnotation(tag = "URL", annotation = keyUrl) { append(host) }
                    }
                    append(sentence.substring(linkStart + host.length))
                } else {
                    append(sentence)
                }
            }
        }
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset)
                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
            }
        )
    }

    OutlinedTextField(
        value = value,
        // Typing only updates what is on screen. See [commit] for when it is stored.
        onValueChange = { value = it },
        // Just "API key": the field sits directly under the engine it belongs to, so naming the
        // engine again is noise — and it read badly for the engines whose own label ends in API
        // ("OpenAI Whisper API API key").
        label = { Text(languageManager.getString("engine_api_key")) },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                val hadFocus = isFocused
                isFocused = focus.isFocused
                if (hadFocus && !focus.isFocused) commit()
            },
        // Hidden until focused, like every other credential field in settings: a key is readable
        // while you are checking what you pasted and masked when someone glances over.
        visualTransformation = if (isFocused) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = !isFocused,
        maxLines = if (isFocused) 5 else 1,
        textStyle = MaterialTheme.typography.bodyMedium
    )
}
