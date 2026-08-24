package com.voxapps.expenses.domain.budget

import com.voxapps.expenses.data.AccountBudget
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetMathTest {

    private val today = LocalDate.of(2026, 8, 24)
    private fun at(day: Int, hour: Int = 12): Long =
        LocalDate.of(2026, 8, day).atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong())
            .toInstant().toEpochMilli()

    private fun expense(
        amount: Double,
        day: Int,
        accountId: Long? = 7L,
        currency: String = "RON",
        direction: TransactionDirection = TransactionDirection.OUTGOING
    ) = Expense(
        title = null, totalAmount = amount, currencyCode = currency, vendor = null, bank = null,
        location = null, dateTime = at(day), comments = null, categoryId = null,
        direction = direction, bankAccountId = accountId
    )

    private val monthly = AccountBudget(
        id = 1, accountId = 7L, currencyCode = "RON", amount = 1500.0,
        mode = AccountBudget.MODE_PERIOD, period = SpendingLimit.PERIOD_MONTHLY
    )

    @Test
    fun `what is left is what was budgeted less what went out`() {
        val spent = listOf(expense(300.0, 3), expense(120.0, 20))
        assertEquals(1080.0, BudgetMath.remaining(monthly, spent, today = today), 0.001)
        assertEquals(420.0, BudgetMath.spent(monthly, spent, today = today), 0.001)
    }

    /** A refund puts the money back in the pot it left. */
    @Test
    fun `an incoming record adds`() {
        val moves = listOf(expense(300.0, 3), expense(100.0, 5, direction = TransactionDirection.INCOMING))
        assertEquals(1300.0, BudgetMath.remaining(monthly, moves, today = today), 0.001)
    }

    @Test
    fun `only this account, this currency, this window`() {
        val noise = listOf(
            expense(50.0, 20, accountId = 9L),
            expense(50.0, 20, currency = "EUR"),
            expense(50.0, 20, accountId = null),
            expense(999.0, 24).copy(dateTime = at(24) - 40L * 24 * 3600_000)
        )
        assertEquals(1500.0, BudgetMath.remaining(monthly, noise, today = today), 0.001)
    }

    /** A card nobody tops up but the employer: the window is the top-up, not the calendar. */
    @Test
    fun `a pot runs from when it was last filled`() {
        val pot = AccountBudget(
            id = 2, accountId = 7L, currencyCode = "RON", amount = 500.0,
            mode = AccountBudget.MODE_POT, startedAt = at(10)
        )
        val moves = listOf(expense(90.0, 5), expense(60.0, 15))
        assertEquals(440.0, BudgetMath.remaining(pot, moves, today = today), 0.001)
    }

    /**
     * A believed statement replaces the arithmetic before it: what the bank said is the opening
     * figure, and only what happened after counts against it.
     */
    @Test
    fun `a reconciled statement is the new starting point`() {
        val reconciled = monthly.copy(reconciledAt = at(15), reconciledRemaining = 900.0)
        val moves = listOf(expense(400.0, 3), expense(100.0, 20))
        assertEquals(800.0, BudgetMath.remaining(reconciled, moves, today = today), 0.001)
    }

    @Test
    fun `a record on the boundary belongs to the window it opens`() {
        val start = BudgetMath.windowStart(monthly, today)
        val held = setOf(monthly.accountId)
        assertTrue(BudgetMath.applies(monthly, expense(10.0, 1).copy(dateTime = start), start, held))
        assertFalse(BudgetMath.applies(monthly, expense(10.0, 1).copy(dateTime = start - 1), start, held))
    }

    /**
     * A card is a way of reaching an account, not a pot of its own: a payment filed against the card
     * comes out of the budget the account carries.
     */
    @Test
    fun `a card spends its account's budget`() {
        val account = BankAccount(
            id = 7L, digits = "RO49AAAA1B31007593840000", kind = "IBAN",
            currencyCode = "RON", createdAt = 0L
        )
        val card = account.copy(id = 8L, digits = "4535", kind = "CARD_TAIL", parentId = 7L)
        val onTheCard = listOf(expense(100.0, 20, accountId = card.id))

        assertEquals(1400.0, BudgetMath.remaining(monthly, onTheCard, listOf(account, card), today), 0.001)
        // Without the tree there is nothing to say the card belongs to the account, and the payment
        // is somebody else's.
        assertEquals(1500.0, BudgetMath.remaining(monthly, onTheCard, emptyList(), today), 0.001)
    }

    /** A card parented elsewhere is not this account's, whatever else it looks like. */
    @Test
    fun `a card under another account is left alone`() {
        val mine = BankAccount(id = 7L, digits = "111", kind = "CARD_TAIL", currencyCode = "RON", createdAt = 0L)
        val theirs = mine.copy(id = 9L, digits = "222", parentId = 5L)
        val moves = listOf(expense(100.0, 20, accountId = theirs.id))
        assertEquals(1500.0, BudgetMath.remaining(monthly, moves, listOf(mine, theirs), today), 0.001)
    }
}
