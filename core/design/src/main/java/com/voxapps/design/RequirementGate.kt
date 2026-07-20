package com.voxapps.design

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The click handler and dimmed-alpha for a control that depends on another app being installed —
 * most commonly Vox Commander's LLM hook (scan, auto-merge, dedupe, ...), which is a fire-and-forget
 * broadcast with no delivery confirmation, so a missing Commander means the request silently
 * vanishes with no error anywhere; a scan entry point also depends on Vox Vision itself being
 * installed before it can even launch. Deliberately keeps the control visible and tappable rather
 * than hiding it (the older convention in this codebase, still fine for read-only indicators) or
 * disabling it outright (`enabled = false` swallows the click event, so the user gets no
 * explanation) — [onClick] here always fires, it just shows [requiredMessage] instead of running
 * [action] when [satisfied] is false.
 */
data class RequirementGate(
    val alpha: Float,
    val onClick: () -> Unit
)

@Composable
fun rememberRequirementGate(
    satisfied: Boolean,
    requiredMessage: String,
    action: () -> Unit
): RequirementGate {
    val context = LocalContext.current
    return RequirementGate(
        alpha = if (satisfied) 1f else 0.4f,
        onClick = {
            if (satisfied) action() else Toast.makeText(context, requiredMessage, Toast.LENGTH_SHORT).show()
        }
    )
}

/**
 * The same "explain why nothing happened" toast as [rememberRequirementGate], for call sites that
 * can't use a Composable — namely a home-screen widget's Glance `ActionCallback`, which runs with a
 * plain [Context] outside any Compose composition. Kept in the same file so the message/behavior
 * can't drift between the in-app and widget versions of this gate.
 */
fun showRequirementToast(context: Context, requiredMessage: String) {
    Toast.makeText(context, requiredMessage, Toast.LENGTH_SHORT).show()
}
