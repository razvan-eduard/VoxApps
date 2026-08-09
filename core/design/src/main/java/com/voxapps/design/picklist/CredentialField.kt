package com.voxapps.design.picklist

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.CommittedTextField

/**
 * A secret, typed and stored the way every secret in settings is: masked until focused, written
 * when the field is finished with, and replaced by an external change only while nobody is editing.
 *
 * A thin wrapper over [CommittedTextField] because the behaviour is not specific to secrets — the
 * Piped endpoint field needed the same and had none of it.
 *
 * The "where do I get one of these" line above the field comes from the service's own declaration
 * and is drawn by [CredentialHelpLink], the same one the connection test uses. It was written out
 * twice before — once here and once beside the test — so a service could explain itself differently
 * depending on which of the two happened to be on screen.
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

    CredentialHelpLink(helpUrl, helpText)

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
