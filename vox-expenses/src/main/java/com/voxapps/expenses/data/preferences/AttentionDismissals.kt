package com.voxapps.expenses.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What a person has said they have seen, per kind of waiting thing. */
data class Dismissals(
    val incompleteBefore: Long = 0L,
    val stagedBefore: Long = 0L,
    val rulesBefore: Long = 0L,
    val queuedBefore: Long = 0L
)

/**
 * "I have seen these" — remembered as a moment rather than as a list.
 *
 * Dismissing cannot mean deleting: an incomplete record is still a record, a drafted rule is still a
 * draft, and throwing either away because somebody wanted a quieter screen would be answering a
 * question nobody asked. It cannot mean hiding for ever either, or the next one that arrives is
 * hidden with it.
 *
 * So it is a line drawn in time: everything older than the moment you dismissed is yours, already
 * seen, and never counted again — and anything that arrives after it is counted, because it is new.
 * One moment per kind, since seeing the incomplete records says nothing about the rules.
 */
class AttentionDismissals(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val INCOMPLETE = longPreferencesKey("attention_dismissed_incomplete")
        val STAGED = longPreferencesKey("attention_dismissed_staged")
        val RULES = longPreferencesKey("attention_dismissed_rules")
        val QUEUED = longPreferencesKey("attention_dismissed_queued")
    }

    val flow: Flow<Dismissals> = dataStore.data.map { prefs ->
        Dismissals(
            incompleteBefore = prefs[Keys.INCOMPLETE] ?: 0L,
            stagedBefore = prefs[Keys.STAGED] ?: 0L,
            rulesBefore = prefs[Keys.RULES] ?: 0L,
            queuedBefore = prefs[Keys.QUEUED] ?: 0L
        )
    }

    suspend fun dismiss(kind: AttentionKind, atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs -> prefs[keyOf(kind)] = atMillis }
    }

    suspend fun dismissAll(atMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs -> AttentionKind.entries.forEach { prefs[keyOf(it)] = atMillis } }
    }

    suspend fun snapshot(): Dismissals = flow.first()

    private fun keyOf(kind: AttentionKind) = when (kind) {
        AttentionKind.INCOMPLETE -> Keys.INCOMPLETE
        AttentionKind.STAGED -> Keys.STAGED
        AttentionKind.RULES -> Keys.RULES
        AttentionKind.QUEUED -> Keys.QUEUED
    }
}

/** The kinds of thing that can wait, and be said to have been seen. */
enum class AttentionKind { INCOMPLETE, STAGED, RULES, QUEUED }
