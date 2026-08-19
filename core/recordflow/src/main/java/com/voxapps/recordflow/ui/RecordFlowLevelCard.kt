package com.voxapps.recordflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel

/**
 * The words this card needs, supplied by the satellite — core has no LanguageManager, for the same
 * reason the rest of `:core:design` takes its strings from the caller.
 *
 * The two "fill in automatically" lines name a satellite's own halves: an expense's coarse fields and
 * its line items are not what a weather record's are, and a label written here could only be vague
 * enough to fit both.
 */
data class RecordFlowStrings(
    val title: String,
    val sendNothing: String,
    val sendNothingDesc: String,
    val sendMissing: String,
    val sendMissingDesc: String,
    val sendHead: String,
    val sendHeadDesc: String,
    val sendEverything: String,
    val sendEverythingDesc: String,
    val fillHead: String,
    /** Null where the record has no fine detail to speak of — see [FlowSupport.weights]. A satellite
     *  without one is not asked to invent a label for it. */
    val fillBody: String? = null,
    /** Why a box is fixed rather than absent — shown where the satellite has nowhere to put a
     *  proposal, so the only honest thing it can do with an answer is write it. */
    val cannotSuggest: String
)

/**
 * One flow's setting, as the two questions it actually is: how much is sent, and how much of the
 * answer is written without anyone reading it.
 *
 * Eight rungs presented as eight names would make a person decode compound labels — "every coarse
 * field, filled in automatically" — to answer two simple questions. So the card asks them
 * separately, and the pair is turned back into the rung that gets stored. Only combinations that
 * exist can be reached: a box the satellite does not support is not shown, and one it cannot honour
 * is shown fixed with the reason beside it.
 *
 * [support] is what the satellite declared it can do here, and it governs everything visible: the
 * scopes offered, whether the boxes appear at all, and whether they can be cleared.
 */
@Composable
fun RecordFlowLevelCard(
    support: FlowSupport,
    level: LlmLevel,
    strings: RecordFlowStrings,
    onLevelChange: (LlmLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    val scopes = AskScope.entries.filter { scope -> support.supported.any { it.asks == scope } }

    SettingsSectionCard(strings.title, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            scopes.forEach { scope ->
                val (label, desc) = when (scope) {
                    AskScope.NOTHING -> strings.sendNothing to strings.sendNothingDesc
                    AskScope.MISSING_HEAD -> strings.sendMissing to strings.sendMissingDesc
                    AskScope.ALL_HEAD -> strings.sendHead to strings.sendHeadDesc
                    AskScope.EVERYTHING -> strings.sendEverything to strings.sendEverythingDesc
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { support.settle(scope, level)?.let(onLevelChange) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = level.asks == scope,
                        onClick = { support.settle(scope, level)?.let(onLevelChange) }
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Nothing is asked, so there is nothing to decide about the answer.
        if (level.asks == AskScope.NOTHING) return@SettingsSectionCard

        FillBox(
            label = strings.fillHead,
            weight = FieldWeight.HEAD,
            support = support,
            level = level,
            strings = strings,
            onLevelChange = onLevelChange
        )
        // Drawn only where there is fine detail to have an opinion about, and only under the widest
        // question, which is the only one that asks about it.
        val bodyLabel = strings.fillBody
        if (FieldWeight.BODY in support.weights && bodyLabel != null && level.asks.covers(FieldWeight.BODY)) {
            FillBox(
                label = bodyLabel,
                weight = FieldWeight.BODY,
                support = support,
                level = level,
                strings = strings,
                onLevelChange = onLevelChange
            )
        }
    }
}

@Composable
private fun FillBox(
    label: String,
    weight: FieldWeight,
    support: FlowSupport,
    level: LlmLevel,
    strings: RecordFlowStrings,
    onLevelChange: (LlmLevel) -> Unit
) {
    val checked = level.applies(weight)
    val target = support.toggled(level, weight)
    // Fixed rather than hidden: a person choosing this level should see that the answer is written,
    // and why it could not be otherwise.
    val fixed = target == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !fixed) { target?.let(onLevelChange) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = !fixed,
            onCheckedChange = { target?.let(onLevelChange) }
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (fixed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            if (fixed) {
                Text(
                    strings.cannotSuggest,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The rung this flow lands on when the scope changes — keeping as much of what is written as the new
 * scope allows, so moving the choice of how much to send does not silently change how much is filled
 * in. Null when the satellite supports no rung at that scope at all.
 */
internal fun FlowSupport.settle(scope: AskScope, from: LlmLevel): LlmLevel? {
    val head = from.applies(FieldWeight.HEAD) && scope.covers(FieldWeight.HEAD)
    val body = from.applies(FieldWeight.BODY) && scope.covers(FieldWeight.BODY)
    return LlmLevel.of(scope, head, body)?.takeIf { it in supported }
        ?: supported.filter { it.asks == scope }.minByOrNull { it.ordinal }
}

/**
 * The rung with this weight's box flipped, or null when this satellite supports none — which is what
 * a box that cannot be cleared means, and the only reason a box is ever fixed.
 *
 * Clearing the coarse box clears the fine one with it. Writing the fine detail while leaving the
 * coarse fields to be approved is the one combination that means nothing, and the choice is between
 * refusing the tap and carrying it through; refusing it would present a box that looks available and
 * does nothing, with no way to reach the state it implies except by working out that another box has
 * to be cleared first.
 */
internal fun FlowSupport.toggled(level: LlmLevel, weight: FieldWeight): LlmLevel? {
    val head: Boolean
    val body: Boolean
    if (weight == FieldWeight.HEAD) {
        head = !level.applies(FieldWeight.HEAD)
        body = if (head) level.applies(FieldWeight.BODY) else false
    } else {
        body = !level.applies(FieldWeight.BODY)
        head = if (body) true else level.applies(FieldWeight.HEAD)
    }
    return LlmLevel.of(level.asks, head, body)?.takeIf { it in supported }
}
