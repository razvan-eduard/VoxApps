# Vox Apps — what actually happens under real use

> Part of the **VoxApps** monorepo. Five scenarios from ordinary life — an interruption, a habit, a
> doubt, a year of wear, no network — answered against the code as it stands, with file references.
> Each ends with a verdict and, where there is one, the gap. Nothing here is aspirational: where the
> answer is "it does not do that", it says so.

## 1. At the till — interruption and persistence

*A receipt is photographed, a phone call arrives, the OS kills the app.*

**The photo.** It is never held in the UI's memory as the only copy. Vision writes the page, and the
expenses side stages the file(s) before any model is involved; when a record id finally exists they
are linked to it as ordinary attachment rows — win or lose
(`LlmResultReceiver.linkPendingScanAttachments`, "never lose the photo"). A parse that fails
outright still produces a record: a **stub** titled *manual review required*, with the photo attached,
`isStub = true`, and the figures the page itself proved (`LlmResultReceiver:735`).

**The request.** `VoxLlmRequestQueue.enqueueAndSend` writes a row into `pending_llm_requests`
(Room, SQLCipher) *before* broadcasting, with an attempt count and a last-attempt stamp
(`core/ipc/VoxLlmRequestQueue.kt`). `PendingLlmRequestRetryWorker` runs every fifteen minutes and
re-dispatches anything unanswered (`core/ipc/PendingLlmRequestRetry.kt`), scheduled at process start
(`ExpensesApplication:53`). The UI being dead is irrelevant — the queue is not in the UI.

**What the user sees afterwards.** The expense simply appears in the list. A stub carries a red
error mark on its card (`ExpenseCards.kt:121`), so "this one needs you" is visible without opening it.

> **Verdict.** Nothing is lost: not the photo, not the request, not the reading. The recovery path is
> the same one a Commander that was briefly uninstalled goes through.
>
> **The gap.** There is no *in-flight* state. Between the shutter and the reply the app shows
> nothing at all, and the finished record arrives with no mark saying it is new. A person who was
> interrupted has no way to ask "did that one go through?" other than scanning the list for it.

## 2. Autopilot — three ticks before coffee

**Taps.** One per node. The node's tap target ticks the item directly and does nothing else while
the row is not being edited (`ToDoNodeTimeline.kt:343`), so three items are three taps, plus one
back. No confirmation, no menu, no undo prompt in the way.

**Is the feedback instant?** No — it is a round trip. `toggleDone` reads the row, writes it back
(`ToDoRepository.kt:172`), and the list redraws when Room's Flow emits (`observeForList`). On a
device this is a frame or two, but it is not optimistic by construction: the colour and the solid
outline follow the database, not the finger.

**A mis-tap on the text.** It opens the inline editor. Closing it is one tap (✕) or back, and an item
nobody named is discarded rather than left behind as an empty row (`ToDoRepository.discardIfUnnamed`).
The editor is in the row rather than over it, so nothing else moves.

> **Verdict.** The frequent action is as short as it can be, and the mis-tap costs one tap.
>
> **The gap.** The tick is honest rather than instant. If it ever feels laggy under load, the fix is
> local optimistic state reconciled by the Flow — which is a deliberate change, not an oversight,
> because an optimistic tick that fails to write is a lie the user acts on.

## 3. Broken trust — which of these did the machine invent?

**Provenance in the editor.** Not shown. A value is a value: the amount the page proved, the vendor
a vocabulary matched, and the restaurant name a model guessed all render identically in
`ExpenseEditScreen`. What *is* marked is anything still being **offered** — rescan corrections appear
as dismissible chips beside the field (`FieldSuggestionChip`), and a notification capture waiting for
review uses the two-colour chip whose green means "a value we believe" and amber means "a question
that will teach the app something" (`VoxSuggestionChip`).

Which of the two a field gets is the rung, not the field: at a SUGGEST rung the model's answers
arrive as chips, at an AUTO rung they are written (`:core:recordflow`, `LlmLevel`). So a person at an
AUTO rung cannot tell, in the editor, what came from arithmetic and what came from a sentence.

**Does correcting the name teach?** Eventually, and without ticking anything. `FieldCorrectionMemory`
records the correction and only counts it as active once it has been seen `fieldCorrectionThreshold`
times (`core/fieldmemory/FieldCorrectionMemory.kt:45`); the default is **MEDIUM**, not instant
(`ExpensesSettings.kt:319`), and the speed is a four-way setting. The same discipline governs re-map
rules: a repeated edit drafts a rule that is **disabled** until a person enables it.

> **Verdict.** Nothing is silently learned from a single correction, and nothing is silently applied
> — but nothing is silently *explained* either.
>
> **The gap.** This is the real one. A per-field origin marker — read from the document, matched from
> a list, answered by a model — is cheap to store (the flow already knows) and would turn "why is this
> wrong" into "of course, it guessed that one". Today the answer lives only in the rung the user
> chose weeks ago.

## 4. Wear — two hundred rules and ten thousand rows

**Finding one rule.** The rules list is searched, not scrolled: the search box matches a rule's name
*and* its trigger values *and* what it sets (`RemapRulesSection.ruleSearchText` through the shared
`RuleCardsSection`), so typing the merchant's name brings up the one card. Each card has its own
enable switch and its own delete; there is also a delete-all, which asks first and says exactly what
it takes. Disabling rather than deleting is available, which is the right first move for a rule that
is only sometimes wrong.

**The list under ten thousand rows.** Here the honest answer is that it will degrade.
`expensesWithDetails` observes **every** row with its items and attachments, and `ExpenseFilter.apply`
filters and sorts that list in Kotlin on every emission and every filter change
(`ExpensesStateManager.kt:111`). The table carries indices on `categoryId` and `uid` only — nothing
on `dateTime` — and no filter reaches SQL at all.

> **Verdict.** Rules age well. The list does not.
>
> **The gap, measured how.** This is reasoned from the code, not benchmarked — I have not run it
> against ten thousand rows. What is certain is the shape: O(n) per change, the whole table resident,
> no paging. The fix is the ordinary one — push date and category into the query, page the list — and
> it is worth doing before a year's data arrives rather than after.

## 5. Offline, five per cent battery

**The sad path.** Nothing is thrown away. If Commander cannot answer — the local engine failed to
load, the process was killed, Battery Saver deferred it — the request row stays queued and the retry
worker re-sends it. But Battery Saver is exactly what defers WorkManager, so "every fifteen minutes"
becomes "when the OS feels like it", which for a phone in a pocket abroad can be hours.

**What the user knows.** It depends entirely on the path:

| path                | if the model never answers                                                        |
|---------------------|-----------------------------------------------------------------------------------|
| receipt scan        | a **stub** appears with the photo and the proved figures, marked in the list        |
| notification capture| the capture is staged in the review queue, visible in Settings → From Notifications |
| **voice**           | **nothing appears at all** — the queue row exists, the UI says nothing              |

> **Verdict.** Two of the three paths degrade gracefully, and the offline rungs (`LlmLevel.NONE`
> upward) mean a person who chose them never depended on a model in the first place.
>
> **The gap.** Voice is the one that fails silently, and it is the path where the user cannot see
> what they produced — they spoke into the air. A pending-requests indicator is missing everywhere:
> the queue is a table nothing reads for display, in the app or in the widget.

## What I would fix first, in order

1. **Per-field origin** on a record — proved, matched, or answered. The flow already knows it; only
   the storing and the showing are missing. It is the difference between a machine that edits your
   data and one that shows its work.
2. **A pending indicator** for voice, and for anything queued: one line in the app and one in the
   widget saying "one capture waiting for an answer". Cheap, and it removes the "did I imagine that?"
   moment entirely.
3. **The list into SQL** — date and category in the query, paging under it — before a year's rows
   arrive rather than after.
4. **An in-flight mark** between the shutter and the reply, so an interrupted capture can be found
   without scanning the list.
