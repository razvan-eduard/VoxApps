package com.voxapps.commander.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A setting typed in full before it is stored — written when the field is finished with, never on
 * each keystroke.
 *
 * Per-keystroke writes are wrong in the same way everywhere, and differently badly depending on what
 * reads them. A credential is stored a dozen times half-typed and truncates itself, because each
 * write returns through the store's change listener and re-initialises the field. An endpoint is
 * worse now that a URL is testable: every character produced a stored value, a new probe URL, and
 * therefore a request — `h`, `ht`, `htt`, each one asked of a host that does not exist.
 *
 * Committing on focus loss and on leaving the screen is what both wanted all along.
 *
 * [identity] resets the editing state when the field starts describing something else — an engine
 * key, a provider name, a service id.
 */
@Composable
fun CommittedTextField(
    stored: String,
    label: String,
    onCommit: (String) -> Unit,
    identity: Any,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    placeholder: String? = null
) {
    var value by remember(identity) { mutableStateOf(stored) }
    var isFocused by remember(identity) { mutableStateOf(false) }

    // Read through rememberUpdatedState so the dispose path commits what is on screen now, not what
    // was there when the effect was created.
    val latestValue by rememberUpdatedState(value)
    val latestStored by rememberUpdatedState(stored)
    val latestCommit by rememberUpdatedState(onCommit)
    val commit = { if (latestValue != latestStored) latestCommit(latestValue) }

    // Leaving the screen counts as finishing: a value typed and then navigated away from is kept.
    DisposableEffect(identity) { onDispose { commit() } }

    // An external change (an import, a restore) replaces what is shown — but never mid-edit.
    LaunchedEffect(stored, isFocused) {
        if (!isFocused && stored != value) value = stored
    }

    OutlinedTextField(
        value = value,
        // Typing only updates what is on screen. See [commit] for when it is stored.
        onValueChange = { value = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                val hadFocus = isFocused
                isFocused = focus.isFocused
                if (hadFocus && !focus.isFocused) commit()
            },
        // A secret is readable while you check what you pasted and masked when someone glances over.
        visualTransformation = if (masked && !isFocused) PasswordVisualTransformation()
        else VisualTransformation.None,
        singleLine = !isFocused,
        maxLines = if (isFocused) 5 else 1,
        textStyle = MaterialTheme.typography.bodyMedium
    )
}
