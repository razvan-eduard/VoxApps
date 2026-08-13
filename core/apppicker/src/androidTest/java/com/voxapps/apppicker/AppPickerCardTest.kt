package com.voxapps.apppicker

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card's selection, default and star state are each held as pending state inside the
 * composable and applied together, so the contract is only observable by driving the UI.
 *
 * Star mode is the case with a caller depending on it beyond cosmetics: an expense app uses it to
 * mark which of the watched payment apps are banks, and that set decides whether a captured
 * notification can name a bank at all. A star silently dropped or applied to the wrong package is
 * not visible on screen — it surfaces later as a record with an empty or wrong field.
 */
@RunWith(AndroidJUnit4::class)
class AppPickerCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val apps = listOf(
        AppPickerEntry("com.bank.one", "Bank One"),
        AppPickerEntry("com.bank.two", "Bank Two"),
        AppPickerEntry("com.wallet.app", "Wallet App")
    )

    // Distinct, unambiguous labels so a selector can never match another node by accident.
    private val strings = AppPickerStrings(
        searchPlaceholder = "SEARCH",
        clear = "CLEAR",
        showAllApps = "ALL",
        showUserApps = "USER",
        showSystemApps = "SYSTEM",
        noAppsFound = "NONE_FOUND",
        expand = "EXPAND",
        collapse = "COLLAPSE",
        noneLabel = "NONE",
        notSelected = "NOT_SELECTED",
        noAppsSelected = "NO_APPS_SELECTED",
        defaultAppSummaryFormat = "%s default",
        appsSelectedNoDefaultFormat = "%d selected",
        starredCountSummaryFormat = "%d selected, %d starred",
        selected = "SELECTED",
        setAsDefault = "SET_STAR",
        removeDefault = "REMOVE_STAR",
        done = "DONE",
        cancel = "CANCEL"
    )

    private class Recorder {
        var applied: List<String>? = null
        var appliedStarred: Set<String>? = null
        var appliedDefault: String? = null
        var defaultWasCalled = false
    }

    private fun setContent(
        selected: List<String>,
        starred: Set<String>,
        recorder: Recorder,
        starMode: Boolean = true
    ) {
        compose.setContent {
            AppPickerCard(
                apps = apps,
                selectedPackages = selected,
                onApply = { recorder.applied = it },
                strings = strings,
                label = "PICKER_LABEL",
                starredPackages = starred,
                onApplyStarred = if (starMode) {
                    { recorder.appliedStarred = it }
                } else null
            )
        }
        compose.onNodeWithText("PICKER_LABEL").performClick()
    }

    @Test
    fun starringDoesNotChangeSelection() {
        val recorder = Recorder()
        setContent(selected = listOf("com.bank.one", "com.bank.two"), starred = emptySet(), recorder = recorder)

        compose.onAllNodesWithContentDescription("SET_STAR")[0].performClick()
        compose.onNodeWithText("DONE").performClick()

        assertEquals(setOf("com.bank.one"), recorder.appliedStarred)
        // The two sets are independent: starring must not add, drop or reorder the selection.
        assertEquals(listOf("com.bank.one", "com.bank.two"), recorder.applied?.sorted())
    }

    @Test
    fun anExistingStarIsCarriedThroughUntouched() {
        val recorder = Recorder()
        setContent(
            selected = listOf("com.bank.one", "com.bank.two"),
            starred = setOf("com.bank.two"),
            recorder = recorder
        )

        compose.onNodeWithText("DONE").performClick()

        // Opening and confirming without touching anything must not clear a star.
        assertEquals(setOf("com.bank.two"), recorder.appliedStarred)
    }

    @Test
    fun starCanBeRemoved() {
        val recorder = Recorder()
        setContent(
            selected = listOf("com.bank.one"),
            starred = setOf("com.bank.one"),
            recorder = recorder
        )

        compose.onNodeWithContentDescription("REMOVE_STAR").performClick()
        compose.onNodeWithText("DONE").performClick()

        assertEquals(emptySet<String>(), recorder.appliedStarred)
    }

    @Test
    fun cancelDiscardsPendingStarChanges() {
        val recorder = Recorder()
        setContent(selected = listOf("com.bank.one"), starred = emptySet(), recorder = recorder)

        compose.onNodeWithContentDescription("SET_STAR").performClick()
        compose.onNodeWithText("CANCEL").performClick()

        // Nothing is applied unless the sheet is confirmed.
        assertNull(recorder.appliedStarred)
        assertNull(recorder.applied)
    }

    @Test
    fun onlySelectedAppsOfferAStar() {
        val recorder = Recorder()
        setContent(selected = listOf("com.bank.one"), starred = emptySet(), recorder = recorder)

        // One selected app, so exactly one star affordance — the unselected two offer none.
        assertEquals(1, compose.onAllNodesWithContentDescription("SET_STAR").fetchSemanticsNodes().size)
    }

    @Test
    fun withoutStarModeNoStarAffordanceIsOffered() {
        val recorder = Recorder()
        setContent(
            selected = listOf("com.bank.one"),
            starred = emptySet(),
            recorder = recorder,
            starMode = false
        )

        assertEquals(0, compose.onAllNodesWithContentDescription("SET_STAR").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("REMOVE_STAR").fetchSemanticsNodes().size)
    }

    @Test
    fun deselectingAStarredAppLeavesTheStarBehind() {
        val recorder = Recorder()
        setContent(
            selected = listOf("com.bank.one"),
            starred = setOf("com.bank.one"),
            recorder = recorder
        )

        // Deselect the only selected app, then confirm.
        compose.onNodeWithText("Bank One").performClick()
        compose.onNodeWithText("DONE").performClick()

        // Pins current behaviour: the star survives deselection, so the applied star set can name a
        // package that is no longer a selected source. Callers that treat the star set as a subset
        // of the selection have to reconcile it themselves.
        assertTrue(recorder.applied?.contains("com.bank.one") != true)
        assertEquals(setOf("com.bank.one"), recorder.appliedStarred)
    }
}
