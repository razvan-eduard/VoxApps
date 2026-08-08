package com.voxapps.commander.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.services.ProbeSpec
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.services.ServiceProbe
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import com.voxapps.design.ConnectionTestAuto

/**
 * Everything a declared service says about its own health, in one place.
 *
 * The same container wherever a service is chosen or configured: under an engine picklist, under a
 * search provider picklist, inside an API integration's card. Before this each screen answered
 * "is it working?" in its own way — a search provider ran a dummy query, an engine probed a URL, an
 * integration only checked whether a token existed locally — so the same question looked different
 * and meant different things depending on where you were standing.
 *
 * Shows, in order and only when it applies:
 *  - where to obtain a credential, for a service that needs one and says where;
 *  - what is known locally about a token, which needs no request to answer;
 *  - whether the service actually answered, which does.
 */
@Composable
fun ConnectionTestCard(
    keys: List<Any?>,
    testFn: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    tokenState: TokenState? = null,
    helpUrl: String? = null,
    helpText: String? = null,
    testingLabel: String = "Testing…",
    onlineLabel: String = "Reachable",
    offlineLabel: String = "Not reachable"
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CredentialHelpLink(helpUrl, helpText)

        tokenState?.let {
            Text(
                text = it.describe(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        ConnectionTestAuto(
            keys = keys,
            testFn = testFn,
            testingLabel = testingLabel,
            onlineLabel = onlineLabel,
            offlineLabel = offlineLabel
        )
    }
}

/**
 * The declared-service form: renders nothing at all when [spec] is null.
 *
 * Null is the honest answer for anything with no endpoint — an on-device engine, or Porcupine, whose
 * key is validated inside its SDK with no URL to call. Showing a permanently failing test for those
 * would be worse than showing none.
 *
 * [extraKeys] carries whatever else should force a re-test — a credential's length, never the
 * credential, since these become composition keys.
 */
@Composable
fun ConnectionTestCard(
    spec: ProbeSpec?,
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier,
    extraKeys: List<Any?> = emptyList(),
    tokenState: TokenState? = null,
    helpUrl: String? = null,
    helpText: String? = null,
    testingLabel: String = "Testing…",
    onlineLabel: String = "Reachable",
    offlineLabel: String = "Not reachable",
    missingCredentialLabel: String = "Needs an API key"
) {
    if (spec == null) return

    ConnectionTestCard(
        keys = listOf(spec.id, spec.url, spec.credential?.length ?: 0) + extraKeys,
        // The deadline every other outbound call in this app obeys, including whatever the engine
        // itself declared — the prober takes a number so it can live beside services rather than
        // beside one app's settings.
        testFn = { ServiceProbe.run(spec, CloudDeadline.secondsFor(spec.id, settingsRepo)) },
        modifier = modifier,
        tokenState = tokenState,
        helpUrl = helpUrl,
        helpText = helpText,
        testingLabel = testingLabel,
        onlineLabel = onlineLabel,
        // No request is made when the credential is missing, so "not reachable" would blame the
        // network for something the field directly above it is asking for.
        offlineLabel = if (spec.missingCredential) missingCredentialLabel else offlineLabel
    )
}

/**
 * What is known about an OAuth token without asking anyone.
 *
 * Kept separate from the probe result because the two answer different questions and fail
 * differently: a token can be present and rejected, or expired and still refreshable. The screen
 * that used to show one green dot for "a token exists" is what this replaces.
 */
data class TokenState(val present: Boolean, val expiresAtMillis: Long = 0L) {
    val expired: Boolean get() = present && expiresAtMillis > 0L && expiresAtMillis < System.currentTimeMillis()

    fun describe(): String = when {
        !present -> "Not connected"
        expired -> "Token expired ${moment(expiresAtMillis)}"
        expiresAtMillis > 0L -> "Token valid until ${moment(expiresAtMillis)}"
        else -> "Token stored"
    }

    /**
     * A time on its own is only unambiguous today.
     *
     * An access token lives about an hour, so "expired 14:32" usually means an hour ago — but the
     * same string on a token that expired last week reads as if it had just lapsed, which is a
     * different problem with a different fix. Anything outside today carries its date.
     */
    private fun moment(millis: Long): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        val today = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

        return if (sameDay) time
        else "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))} $time"
    }
}

/** "Get a key at <host>", from the service's own declaration — shared by the card and the key field. */
@Composable
private fun CredentialHelpLink(helpUrl: String?, helpText: String?) {
    val host = helpUrl?.toUri()?.host ?: return
    val uriHandler = LocalUriHandler.current

    val annotated = remember(helpText, host, helpUrl) {
        buildAnnotatedString {
            val sentence = helpText ?: host
            val start = sentence.indexOf(host)
            if (start >= 0) {
                append(sentence.substring(0, start))
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    withAnnotation(tag = "URL", annotation = helpUrl) { append(host) }
                }
                append(sentence.substring(start + host.length))
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
