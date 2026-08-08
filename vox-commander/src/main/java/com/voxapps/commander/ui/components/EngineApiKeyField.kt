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

    // Where to get the key, when the engine says. A localized sentence if it declares one (the host
    // inside it becomes the link), otherwise just the host as a link — so an engine can be useful
    // here with a single line of JSON and no translation work.
    val helpKey = RemoteModelRegistry.declaredApiKeyHelpKey(engineKey)
    val helpText = helpKey?.let { languageManager.getString(it) }?.takeIf { it.isNotBlank() && it != helpKey }

    CredentialField(
        stored = uiState.credentials.forEngine(engineKey) ?: "",
        label = languageManager.getString("engine_api_key"),
        onCommit = {
            appStateManager.setEngineApiKey(engineKey, it.ifBlank { null })
            onKeyChanged(it)
        },
        identity = engineKey,
        modifier = modifier,
        helpUrl = RemoteModelRegistry.declaredApiKeyUrl(engineKey),
        helpText = helpText
    )
}

/**
 * A secret, typed and stored the way every secret in settings is: masked until focused, written
 * when the field is finished with, and replaced by an external change only while nobody is editing.
 *
 * A thin wrapper over [CommittedTextField] because the behaviour is not specific to secrets — the
 * Piped endpoint field needed the same and had none of it.
 */
@Composable
fun CredentialField(
    stored: String,
    label: String,
    onCommit: (String) -> Unit,
    identity: Any,
    modifier: Modifier = Modifier,
    helpUrl: String? = null,
    helpText: String? = null
) {
    Spacer(modifier = Modifier.height(8.dp))

    // Where to get the key, when the service says. A localized sentence if one is declared (the host
    // inside it becomes the link), otherwise just the host as a link — so a service can be useful
    // here with a single line of JSON and no translation work.
    val host = helpUrl?.toUri()?.host
    if (host != null && helpUrl != null) {
        val uriHandler = LocalUriHandler.current
        val annotated = remember(helpText, host, helpUrl) {
            buildAnnotatedString {
                val sentence = helpText ?: host
                val linkStart = sentence.indexOf(host)
                if (linkStart >= 0) {
                    append(sentence.substring(0, linkStart))
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        withAnnotation(tag = "URL", annotation = helpUrl) { append(host) }
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

    CommittedTextField(
        stored = stored,
        // Just "API key": the field sits directly under whatever it belongs to, so naming that again
        // is noise — and it read badly for the engines whose own label ends in API ("OpenAI Whisper
        // API API key").
        label = label,
        onCommit = onCommit,
        identity = identity,
        modifier = modifier,
        masked = true
    )
}
