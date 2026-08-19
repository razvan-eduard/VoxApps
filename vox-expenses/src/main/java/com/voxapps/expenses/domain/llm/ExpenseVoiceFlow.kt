package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.recordflow.AskScope
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
 * The rung is fixed at the fullest for now, and the declaration says so rather than implying more.
 * Narrowing it would mean reading an amount out of speech on the device, which
 * `:core:textmatch` can do and this flow does not yet — a real next step, and not something to
 * promise before it is there.
 */
class ExpenseVoiceFlow(
    private val context: Context,
    private val container: ExpensesContainer
) : RecordFlowSpec<String, String, ExpenseParseResultParser.Parsed> {

    override val source = RecordSource.VOICE
    override val support: FlowSupport = ExpensesSettings.VOICE_FLOW_SUPPORT
    override val taskId = LlmTasks.EXPENSE_PARSE

    /** Nothing is established here yet — the words are Commander's to hear. */
    override suspend fun read(input: String): DeterministicReading<String> =
        DeterministicReading(fields = input, usable = input.isNotBlank(), complete = false)

    override suspend fun prompt(reading: DeterministicReading<String>, asks: AskScope): String =
        buildTemplate().replace(
            com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER,
            reading.fields
        )

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
            preParse = null
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
