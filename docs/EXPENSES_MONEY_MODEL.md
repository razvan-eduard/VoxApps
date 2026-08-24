# Vox Expenses — the money model

> Part of the **VoxApps** monorepo (module `:vox-expenses`, `com.voxapps.expenses`). What a record
> knows about money: the bank, the name, the shop, the currency, the card, and how they hang
> together. For what is *left* to spend and how it is worked out, see
> [Budget flow](EXPENSES_BUDGET_FLOW.md).

## Rows in the database

    categories                bank_accounts                     account_budgets
      id                        id                                id
      name                      digits        <- the identity      accountId  --+
      colorArgb                 kind  IBAN|CARD|CARD_TAIL          currencyCode | unique
      icon                      parentId --+  one level only       amount       | together
      isDefault  <- fallback    label      |  a free alias         mode  PERIOD|POT
      position                  bankName   |  text                 period, startedAt
      createdAt                 currencyCode                       reconciledAt
                                icon, autoCreated                  reconciledRemaining
                                           |                                   |
                                 <---------+                        <----------+
    expenses
      id, uid
      totalAmount        \
      currencyCode        }  the facts — no rule rewrites these
      dateTime           /
      vendor      text
      bank        text
      location    text
      title, comments
      direction   OUTGOING | INCOMING
      categoryId      ----------------->  categories.id
      bankAccountId   ----------------->  bank_accounts.id   (the card OR the account, whichever was recognised)
      source  VOICE | SCAN | NOTIFICATION

**Vocabularies are not tables.** They live in settings (DataStore) plus a signed schema file, and
there are four: banks (76 supplied + yours - the ones switched off), legal forms, shops (yours only),
stop words. Their job is recognition inside a message, not storage.

**Currencies are not a table either.** `CurrencyCodes` in `:core:textmatch` holds the ISO codes and
the ways people write them. Settings hold three: the app's own currency, the currency of a new
expense, and the currency of new cards (which may say "from the capture").

## What points at what

- **Expense -> account/card**: one id. A notification carrying `**4535` files it on the card, one
  carrying an IBAN on the account, and a message with no account format leaves it null.
- **Card -> account**: `parentId`, one level. A card with no parent *is* an account.
- **Budget -> (account, currency)**: unique per pair. An account holding RON and EUR carries two.
  Cards hold none: their spending comes out of the account family's budget.
- **Expense -> budget**: no stored link at all. It is derived on every read — same account family,
  same currency, inside the current window.
- **`expenses.bank` vs `bank_accounts.bankName`**: two fields, both text. The first is what that
  message said; the second is who the account is held with. A message can name either alone.

## Where each value comes from at capture

| field            | source, in order                                                                   |
|------------------|------------------------------------------------------------------------------------|
| amount           | read from the text (figures marked by a currency)                                   |
| currency         | read 1:1 from the text -> what a model said -> the default                          |
| vendor / bank    | vocabulary (token-sequence match) -> model                                          |
| account/card     | `AccountIdentifiers` on the digits -> existing row, else created; auto-parented when that bank has exactly one account |
| category         | rules -> name resolution -> the fallback category                                   |
| budget           | nothing is written — it is recomputed                                               |

## What each field offers in the UI

| field              | the list                                   | search also reaches        | "new" adds to      |
|--------------------|--------------------------------------------|----------------------------|--------------------|
| Vendor             | shops you have paid                        | your own shop vocabulary   | the vocabulary     |
| Bank (expense)     | banks your records and accounts name        | all 76 supplied + yours    | the vocabulary     |
| Bank (account)     | the same                                    | the same                   | the vocabulary     |
| Belongs to         | accounts that may be a parent               | —                          | creates an account |
| Currency           | codes the reader knows, your language first | —                          | —                  |

The rule applied throughout: **the list shows what you use; the dictionary stays one search away.**
