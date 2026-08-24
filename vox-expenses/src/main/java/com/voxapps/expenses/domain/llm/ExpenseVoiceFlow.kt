package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.recordflow.AskScope
import com.voxapps.expenses.data.ExpenseOrigins
import com.voxapps.recordflow.FieldOrigin
import com.voxapps.recordflow.DeterministicReading
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.RecordFlowSpec
import com.voxapps.recordflow.RecordSource
import kotlinx.coroutines.flow.first

private const val TAG = "ExpenseVoiceFlow"

/**
 * A spoken utterance becoming an expense.
 *
 * Unlike a note, a sentence is only half an expense: an amount can be spoken plainly, but which
 * merchant a name refers to and which category a purchase belongs to are judgements. So this flow
 * does have something to ask, and its question is written as a *template* rather than a sentence —
 * Commander hears the words and puts them in, which is why [promptTemplate] exists at all.
 *
 * The sentence does reach [read] on both routes — this app's own queue holds it when this app asked,
 * and `VoxLlmResult.input` carries it back when Commander asked from a cached template. It is still
 * read for nothing, and that is the honest state rather than an oversight.
 *
 * The one thing a rule could settle is the amount, and it cannot. A single currency-marked figure is
 * not the total: in "three loaves at ten each" the marked figure is the per-unit price, and telling
 * that from a cumulative one is the distributive/cumulative distinction — see
 * [DistributiveCumulativeRule], which is most of what this prompt teaches. That distinction is
 * carried by language, so a rule for it would need per-language markers, and a rule with a known
 * mislabel class is a guess wearing a rule's clothes. Hence one rung.
 *
 * What the recovered sentence is good for is checking rather than extracting: a figure the answer
 * reports that nobody spoke was invented. That is a guard for every rung, not a rung of its own.
 */
class ExpenseVoiceFlow(
    private val context: Context,
    private val container: ExpensesContainer
) : RecordFlowSpec<String, String, ExpenseParseResultParser.Parsed> {

    override val source = RecordSource.VOICE
    override val support: FlowSupport = ExpensesSettings.VOICE_FLOW_SUPPORT
    override val taskId = LlmTasks.EXPENSE_PARSE

    /** The words, established as nothing — see the class note for why no rule may touch them. */
    override suspend fun read(input: String): DeterministicReading<String> =
        DeterministicReading(fields = input, usable = input.isNotBlank(), complete = false)

    override suspend fun prompt(reading: DeterministicReading<String>, asks: AskScope): String =
        buildTemplate().replace(com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER, reading.fields)

    /** The same question with the words left out, for the transport that supplies them. */
    override suspend fun promptTemplate(asks: AskScope): String = buildTemplate()

    private suspend fun buildTemplate(): String {
        val settings = container.settingsRepository.getSnapshot()
        val categories = container.expensesRepository.categories.first().map { it.name }
        return ExpenseParsePromptBuilder.buildTemplate(categories, settings.defaultCurrency, settings.language)
    }

    override suspend fun parse(reply: String): ExpenseParseResultParser.Parsed? =
        ExpenseParseResultParser.parse(reply)

    override suspend fun commit(
        reading: DeterministicReading<String>?,
        parsed: ExpenseParseResultParser.Parsed?,
        applies: (FieldWeight) -> Boolean
    ): Long? {
        val answer = parsed ?: return null
        return com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = answer,
            imageName = null,
            preParse = null,
            // Nothing here was proved by anything: a sentence spoken aloud carries no arithmetic to
            // check it against, so every field the record has is the model's reading of it.
            origins = mapOf(
                FieldOrigin.ANSWERED to buildSet {
                    if (answer.title != null) add(ExpenseOrigins.FIELD_TITLE)
                    add(ExpenseOrigins.FIELD_AMOUNT)
                    if (answer.currency != null) add(ExpenseOrigins.FIELD_CURRENCY)
                    if (answer.vendor != null) add(ExpenseOrigins.FIELD_VENDOR)
                    if (answer.bank != null) add(ExpenseOrigins.FIELD_BANK)
                    if (answer.location != null) add(ExpenseOrigins.FIELD_LOCATION)
                    if (answer.category != null) add(ExpenseOrigins.FIELD_CATEGORY)
                    if (answer.date != null) add(ExpenseOrigins.FIELD_DATE)
                    if (answer.items.isNotEmpty()) add(ExpenseOrigins.FIELD_ITEMS)
                }
            )
        ).takeIf { it > 0 }
    }

    /**
     * Nothing said out loud is kept unfinished: a spoken expense the model could not read leaves no
     * record, and the speaker is the one who knows what they said. The failure is announced where it
     * happens rather than filed here.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<String>?,
        parsed: ExpenseParseResultParser.Parsed?
    ) = Unit
}
