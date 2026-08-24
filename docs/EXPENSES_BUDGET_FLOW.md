# Vox Expenses — budget flow, downstream

> Part of the **VoxApps** monorepo (module `:vox-expenses`, `com.voxapps.expenses`). The companion
> to [the money model](EXPENSES_MONEY_MODEL.md): that one says what points at what, this one follows
> the money down — what holds it, what draws on it, what is counted, and what is deliberately not.

## The chain

    BUDGET            one per (account, currency)          "1500 RON this month"
      |                account_budgets
      |
      v
    ACCOUNT           the row money lives in               ING, RO49...0000
      |                bank_accounts, parentId = null
      |
      +--> CARD       a way of reaching that account       ING **4535   (parentId -> account)
      +--> CARD       another way of reaching it           ING **9999
      |                cards hold no budget of their own
      v
    EXPENSE           filed against whichever row was recognised
                       expenses.bankAccountId + expenses.currencyCode

An expense filed on a card draws on its account's budget. An expense filed on the account draws on
the same budget. An expense filed on nothing (no account format in the message) draws on no budget
at all — and that is honest, not a gap: the app cannot say whose money it was.

## What is stored, what is worked out

Stored is only the intent:

    amount                what the period grants, or what the pot was filled with
    mode                  PERIOD | POT
    period                WEEKLY | MONTHLY        (PERIOD only)
    startedAt             when the pot was filled (POT only)
    reconciledAt          when a statement was believed        (nullable)
    reconciledRemaining   what that statement said was left    (nullable)

**Nothing is ever decremented.** What is left is computed on every read:

    windowStart   = max( mode's own start , reconciledAt )
                      PERIOD -> the calendar's window (SpendingPeriod: this week / this month)
                      POT    -> startedAt

    opening       = reconciledRemaining ?: amount

    movement      = sum over expenses where
                        expense.bankAccountId is in the account's family (account + its cards)
                        AND expense.currencyCode == budget.currencyCode   (case-insensitive)
                        AND expense.dateTime >= windowStart
                    counting  -amount for OUTGOING, +amount for INCOMING

    remaining     = opening + movement
    spent         = opening - remaining

The alternative — a running figure decremented as records arrive — is wrong the first time a capture
is missed, edited or deleted, and stays wrong with nothing to notice it. Derived, whatever the
expense list says today is what the budget says today.

## What counts, what does not

| a record …                              | counted? | why                                                     |
|-----------------------------------------|----------|---------------------------------------------------------|
| on the account                          | yes      | the money left there                                     |
| on a card under the account             | yes      | the card is a way of reaching that account               |
| on a card under a *different* account   | no       | different money                                          |
| in another currency the account holds   | no       | that currency has its own budget                         |
| dated before the window                 | no       | it belonged to the period that has closed                |
| dated before a believed statement       | no       | the bank had already subtracted it                       |
| incoming (refund, transfer in)          | yes, adds| money that came back into the same pot                   |
| on an archived card                     | yes      | it still left the account; archiving is a display fact   |
| with no account at all                  | no       | nothing says whose money it was                          |

## Believing a statement

Most bank notifications state the balance left. Taking one is not an adjustment, it is a new
starting point:

    before:  1500 granted, we saw 3 payments, remaining 1080
    bank says "disponibil 1043.20" at 15:12

    after:   reconciledAt = 15:12, reconciledRemaining = 1043.20
             remaining = 1043.20 + (only what happens after 15:12)

Everything the app may have missed before that moment stops mattering from that moment. This is why
the pair is stored rather than the difference: a difference would have to be re-applied for ever,
while a starting point simply replaces what came before it.

## Upward again: one figure for a glance

The widget header is the only place budgets are added together.

    OFF        nothing is drawn at all — a home screen is read on a lock screen and over shoulders
    TOTAL      every budget
    SELECTED   only the accounts ticked

    all budgets in one currency  ->  plain sum, stated in that currency, no rate involved
    mixed currencies             ->  each converted into the home currency
                                     a budget with no fetched rate is left out, never added as if
                                     it were already home currency
    nothing convertible          ->  no header rather than a wrong number

## Two things next to each other that are not the same

**Budget** — per account and currency, a pot or a period, answers "how much is left to spend".
**Spending limit** — per category, per period, in the home currency, answers "tell me when I have
gone past this". They share a settings page (*Budget and spending limits*) and nothing else: one is
the plan, the other is the guard on it, and neither is computed from the other.

## Worked example

    Account: ING, RO49...0000, RON              budget: 1500 RON, monthly
      card ING **4535 (archived on the 12th)
      card ING **9999 (issued on the 12th)

    Aug 3   LIDL          315.07 RON   on **4535     -> counted    remaining 1184.93
    Aug 9   refund         50.00 RON   on **4535 in  -> counted +  remaining 1234.93
    Aug 12  card replaced, **4535 archived                          nothing changes
    Aug 14  BRISTOL MED    60.00 RON   on **9999     -> counted    remaining 1174.93
    Aug 15  bank states "1043.20 available"                        opening becomes 1043.20 at Aug 15
    Aug 20  SIMARSI       108.13 RON   on **9999     -> counted    remaining  935.07
    Aug 24  EUR purchase   12.00 EUR   on **9999     -> not counted (the account's EUR budget, if any)
    Sep 1   the window rolls over                                   remaining 1500.00 again

## What was deliberately not built

- **No budget per card.** Two cards on one account spend the same money; two budgets over it would
  count that money twice, and the sum of all budgets would stop meaning anything.
- **No automatic budget from past spending.** What you meant to spend is a decision, not an average.
- **No rewriting of an expense's account** when a card is archived or re-parented. The record says
  which card paid, because that is what happened.
