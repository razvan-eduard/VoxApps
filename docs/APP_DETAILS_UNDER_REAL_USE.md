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
`isStub = true`, and the figures the page itself proved (`LlmResultReceiver.createStubExpense`).

**The request.** `VoxLlmRequestQueue.enqueueAndSend` writes a row into `pending_llm_requests`
(Room, SQLCipher) *before* broadcasting, with an attempt count and a last-attempt stamp
(`core/ipc/VoxLlmRequestQueue.kt`). `PendingLlmRequestRetryWorker` runs every fifteen minutes and
re-dispatches anything unanswered (`core/ipc/PendingLlmRequestRetry.kt`), scheduled at process start
(`PendingLlmRequestScheduler.ensureScheduled`, called from `ExpensesApplication.onCreate`). The UI being dead is irrelevant — the queue is not in the UI.

**What the user sees afterwards.** The expense simply appears in the list. A stub carries a red
error mark on its card (the `isStub` branch in `ExpenseCard`), so "this one needs you" is visible without opening it.

> **Verdict.** Nothing is lost: not the photo, not the request, not the reading. The recovery path is
> the same one a Commander that was briefly uninstalled goes through.
>
> **The gap.** The pending strip (§5) says *that* something is between the shutter and the reply,
> but not *which* record it will become, and the finished record arrives with no mark saying it is
> new. A person who was interrupted can see something is in flight, but still finds the result by
> scanning the list for it.

## 2. Autopilot — three ticks before coffee

**Taps.** One per node. The node's tap target ticks the item directly and does nothing else while
the row is not being edited (the node's `onToggleDone` tap target in `ToDoNodeTimeline`), so three items are three taps, plus one
back. No confirmation, no menu, no undo prompt in the way.

**Is the feedback instant?** No — it is a round trip. `toggleDone` reads the row, writes it back
(`ToDoRepository.toggleDone`), and the list redraws when Room's Flow emits (`observeForList`). On a
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
times (`FieldCorrectionMemory.activeCorrections`); the default is **MEDIUM**, not instant
(`ExpensesSettings.fieldCorrectionThreshold`), and the speed is a four-way setting. The same discipline governs re-map
rules: a repeated edit drafts a rule that is **disabled** until a person enables it.

> **Verdict.** Nothing is silently learned from a single correction, and nothing is silently applied
> — but nothing is silently *explained* either.
>
> **What was done about it.** Every record now carries where each of its fields came from
> (`Expense.originsJson`, `:core:recordflow`'s `FieldOrigin`), written by whichever path made it, and
> the editor draws a small mark beside the field: 👁 read from the document, ☑ matched from your
> lists, ✨ answered by the AI. A field somebody edits stops claiming anything but them. Nothing is
> marked for a value with no story, since marking everything makes the marks noise.

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
(the `combine` over `expensesWithDetails` in `ExpensesStateManager`). The table carries indices on `categoryId` and `uid` only — nothing
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
> **What was done about it.** The queue is read for display now: a strip above the list, and a line
> in the widget, saying how many captures are waiting for an answer. It is absent when nothing is
> waiting rather than present and empty, and it opens onto the captures themselves — what each one
> is, when it was sent, how many times it has been re-sent — with a way to ask for them to be tried
> again now instead of at the worker's next turn. Voice still produces no record until the answer arrives —
> that is correct, since nothing about a sentence is certain before it is read — but the person can
> now see that it is being worked on rather than wonder whether they were heard.

## 6. Reading in place — LiveView on a moving hand

**The happy path.** The phone hovers over a label; within a second of holding still, chips appear on
the recognized lines. The hand drifts, the chips drift with it — the affine map of the document
rectangle, not a re-read. The person taps WhatsApp on the phone-number line and the chat opens with
the full international number, because the label's own `.ro` domain named the prefix.

**The sad path.** The detector loses the rectangle for a second — a shadow, a tilt. Nothing happens:
chips clear only on *sustained* absence, at an eagerness the person chose, and the default demands
seven-plus seconds of genuinely empty detection. A read that lands on a blur returns nothing and
retries on its own a few seconds later. If recognition misreads a line, every chip is an offer and
not an act — a wrong number opens a prefilled dialer, it never places a call.

**What the user knows.** The frame pulses while the OCR pass holds the camera thread — deliberate
motion over the frozen rectangle, so a second of stillness reads as thinking rather than hanging.
The frozen style makes the whole exchange explicit: the frame stops, the fields line up as a table,
and nothing moves until retry or close.

> **Verdict.** The mode never writes anything anywhere — no records, no queue, no IPC. The worst a
> bad read can cost is a tap that opens the wrong draft.

## What I would fix first, in order

1. ~~**Per-field origin** on a record~~ — done: stored at capture, shown beside the field, replaced
   by "you did" the moment somebody edits it.
2. ~~**A pending indicator**~~ — done: a strip in the app and a line in the widget, absent while
   there is nothing waiting.
3. **The list into SQL** — date and category in the query, paging under it — before a year's rows
   arrive rather than after. Still open, and now the largest of these.
4. **An in-flight mark** between the shutter and the reply, so an interrupted capture can be found
   without scanning the list. Partly answered by the pending strip, which says *that* something is
   in flight but not *which* record it will become.
