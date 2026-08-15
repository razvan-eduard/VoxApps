package com.voxapps.design.picklist

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.voxapps.services.ServiceEntry

/**
 * Choose a declared service, and get everything that service says about itself underneath.
 *
 * Engines, search providers and rate providers are the same kind of thing — something declared, with
 * an optional credential, an optional endpoint to probe and optionally a list of files of its own.
 * Four screens assembled those three pieces by hand, in three different orders: search put the key
 * field above the test, currency put a help line above both, and the engine tabs put the test above
 * a key field that lived outside the picklist entirely. Here the order is fixed and each part
 * appears only when the selected entry declares it:
 *
 *  0. [notes], for anything no declaration covers;
 *  1. the credential field, when [ServiceEntry.requiresCredential];
 *  2. the connection test, when the entry gives a [ServiceEntry.probeSpec];
 *  3. [models], when [ServiceEntry.hasDownloadableModels].
 *
 * An entry that declares none of them renders nothing beneath the button, calls no lambda and makes
 * no request — an on-device engine, the platform TTS, a local model runtime.
 *
 * [models] is a slot rather than something this component draws, because a model list is per-row
 * download state that only an app knows how to produce. Where a credential is *stored* is likewise
 * the caller's: [credentialFor] and [onCredentialCommit] take it, because a search provider borrows
 * the credential of the engine it is built on rather than owning one.
 */
@Composable
fun <T : ServiceEntry> ServicePicklist(
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    credentialFor: (T) -> String?,
    onCredentialCommit: (T, String?) -> Unit,
    credentialLabel: String,
    modifier: Modifier = Modifier,
    itemEnabled: (T) -> Boolean = { true },
    disabledSuffix: (T) -> String = { "" },
    itemNote: (T) -> String = { "" },
    /** The sentence around the "where to get a key" link, already translated. */
    helpTextFor: (T) -> String? = { null },
    /** How long to give the probe. Null leaves it to the prober's own default. */
    timeoutSecondsFor: (T) -> Int? = { null },
    testingLabel: String = "Testing…",
    onlineLabel: String = "Reachable",
    offlineLabel: String = "Not reachable",
    missingCredentialLabel: String = "Needs an API key",
    noNetworkLabel: String = "No connection",
    anchor: @Composable (label: String, onClick: () -> Unit) -> Unit = { label, onClick ->
        PicklistButtonAnchor(label, onClick)
    },
    /** Anything about the selection that no declaration covers — "requires location", say. Drawn
     *  first, above the credential field, because it is context for what follows. */
    notes: @Composable () -> Unit = {},
    models: @Composable () -> Unit = {}
) {
    Picklist(
        items = items,
        selected = selected,
        itemLabel = itemLabel,
        onSelect = onSelect,
        modifier = modifier,
        itemEnabled = itemEnabled,
        disabledSuffix = disabledSuffix,
        itemNote = itemNote,
        anchor = anchor
    ) {
        val entry = selected ?: return@Picklist
        val credential = credentialFor(entry)

        notes()

        if (entry.requiresCredential) {
            CredentialField(
                stored = credential.orEmpty(),
                label = credentialLabel,
                onCommit = { entered -> onCredentialCommit(entry, entered.ifBlank { null }) },
                // The provider's own id, so switching provider empties the field instead of
                // offering one service's key to another.
                identity = entry.credentialOwnerId,
                helpUrl = entry.apiKeyUrl,
                helpText = helpTextFor(entry)
            )
        }

        // The credential on screen is what gets tested, rather than whatever copy a registry was
        // last handed — so the answer is about the key the user is looking at.
        ConnectionTestCard(
            spec = entry.probeSpec(credential),
            timeoutSeconds = timeoutSecondsFor(entry),
            testingLabel = testingLabel,
            onlineLabel = onlineLabel,
            offlineLabel = offlineLabel,
            missingCredentialLabel = missingCredentialLabel,
            noNetworkLabel = noNetworkLabel
        )

        if (entry.hasDownloadableModels) models()
    }
}
