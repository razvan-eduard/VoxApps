package com.voxapps.expenses.domain.llm

import android.content.Context
import android.widget.Toast
import com.voxapps.expenses.data.ExpenseSource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "ExpenseVoiceFlow"

/**
 * A spoken utterance becoming an expense.
 *
 * Unlike a note, a sentence is only half an expense: an amount can be spoken plainly, but which
 * merchant a name refers to and which category a purchase belongs to are judgements. So this flow
 * does have something to ask, and its question is written as a *template* rather than a sentence —
 * Commander hears the words and puts them in, which is why [promptTemplate] exists at all. The
 * sentence reaches [read] on both routes — this app's own queue holds it when this app asked, and
 * `VoxLlmResult.input` carries it back when Commander asked from a cached template.
 *
 * Two rungs. At the full one the model answers, and the record carries the sentence in its
 * comments, so a misheard figure can be checked against what was said. At the offline one nothing
 * is extracted, because no rule safely can: the one candidate is the amount, and a single
 * currency-marked figure is not the total — in "three loaves at ten each" the marked figure is the
 * per-unit price, and telling that from a cumulative one is the distributive/cumulative distinction
 * (see [DistributiveCumulativeRule]), carried by language rather than arithmetic. So the offline
 * rung files the sentence as a stub awaiting review — [queueForReview] — and never writes a
 * finished record on its own.
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
        // A reply landing at a rung that accepts nothing back is filed, not written — the level
        // moved to the offline rung while this question was in flight.
        if (!applies(FieldWeight.HEAD)) {
            queueForReview(reading, parsed)
            return null
        }
        return com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
            appContext = context.applicationContext,
            container = container,
            parsed = answer,
            imageName = null,
            preParse = null,
            comments = reading?.fields?.takeIf { it.isNotBlank() },
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
     * The words, kept where a person will finish them: a stub expense whose comments carry the
     * sentence. Reached from the offline rung at dispatch and from a reply that could not be read.
     */
    override suspend fun queueForReview(
        reading: DeterministicReading<String>?,
        parsed: ExpenseParseResultParser.Parsed?
    ) {
        val transcript = reading?.fields?.takeIf { it.isNotBlank() } ?: return
        val settings = container.settingsRepository.getSnapshot()
        container.expensesRepository.addExpense(
            title = container.languageManager.getString("manual_review_required"),
            totalAmount = 0.0,
            currencyCode = settings.defaultCurrency,
            vendor = null,
            location = null,
            dateTime = System.currentTimeMillis(),
            comments = transcript,
            categoryId = settings.defaultVoiceCategoryId,
            isStub = true,
            source = ExpenseSource.VOICE
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                container.languageManager.getString("manual_review_required"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
