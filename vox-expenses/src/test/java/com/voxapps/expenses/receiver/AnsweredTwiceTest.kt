package com.voxapps.expenses.receiver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One capture can be answered twice.
 *
 * The request queue re-sends a stored row rather than creating a second one, so a notification that
 * is captured again — the last-chance sweep on dismissal, the force-check button, a retry — goes out
 * under the id it already had and comes back under it too. Both replies are real, and only the first
 * means anything.
 *
 * Left alone the second one reached the insert and was refused there by the unique `uid`, which is
 * the right outcome reported the wrong way: the user was told their payment could not be saved while
 * it sat in the list in front of them.
 */
class AnsweredTwiceTest {

    private fun source(name: String): String =
        listOf(
            "src/main/java/com/voxapps/expenses/receiver/$name.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/receiver/$name.kt"
        ).map(::File).first { it.exists() }.readText()

    private fun repository(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/data/ExpensesRepository.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/data/ExpensesRepository.kt"
        ).map(::File).first { it.exists() }.readText()

    /**
     * The check has to happen while the row still exists — clearing it is precisely what marks the
     * capture answered, so a check placed after `markFulfilled` would reject the *first* reply too
     * and no notification would ever be filed. Order is the whole correctness of it, and order is a
     * property of the code rather than of any single call.
     */
    @Test
    fun `the second-reply guard runs before the request is cleared`() {
        val text = source("LlmResultReceiver")
        val branch = text.indexOf("LlmTasks.NOTIFICATION_EXPENSE_PARSE ->")
        assertTrue("the notification branch must exist", branch > 0)

        val guard = text.indexOf("isPending(requestId)", branch)
        val cleared = text.indexOf("markFulfilled(requestId)", branch)
        assertTrue("the guard must exist", guard > branch)
        assertTrue("and must come before the row is cleared", guard < cleared)
    }

    /** A failure worth retrying must not be treated as an answer, or the retry never happens. */
    @Test
    fun `a retryable failure still returns before anything is cleared`() {
        val text = source("LlmResultReceiver")
        val branch = text.indexOf("LlmTasks.NOTIFICATION_EXPENSE_PARSE ->")
        val retryable = text.indexOf("if (isRetryableFailure) return@launch", branch)
        val guard = text.indexOf("isPending(requestId)", branch)
        assertTrue("the retryable early return comes first of all", retryable in (branch + 1) until guard)
    }

    /**
     * And the insert's own answer for "this is already stored" is not a failure. It stays distinct
     * from the -1 a real failure returns, so one can be silent while the other still speaks up.
     */
    @Test
    fun `an already-present record has its own answer, apart from failure`() {
        val repo = repository()
        assertTrue(repo.contains("const val ALREADY_PRESENT_RESULT"))
        assertTrue(
            "a constraint violation must be caught before the generic failure arm",
            repo.indexOf("SQLiteConstraintException") < repo.indexOf("DB Insert FAILED")
        )

        // The reply handler tests one named set rather than enumerating sentinels, so a new one
        // cannot be added to the repository and forgotten by the arm that reports failures. What
        // the set must contain is asserted on the values themselves — see NotInsertedIsNotFailedTest.
        val receiver = source("LlmResultReceiver")
        val silent = receiver.indexOf("newExpenseId in RECOGNIZED_NOT_INSERTED")
        val complains = receiver.indexOf("scan_save_failed", maxOf(silent, 0))
        assertTrue("the already-there outcomes are handled as a set", silent > 0)
        assertTrue("and handled before the arm that complains", silent < complains)
    }
}
