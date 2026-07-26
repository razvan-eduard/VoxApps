# Vox Apps — Feature Reference

Detailed feature-level documentation for each companion app in the VoxApps monorepo, moved out of the
top-level [`README.md`](../README.md) to keep that file a short, non-technical overview. For how these
apps talk to Commander and each other (the cross-app contract, `:core:ipc`, discovery, routing,
day-linking, etc.), see [`TECHNICAL_DOCUMENTATION.md`'s §19 Vox Apps Ecosystem](TECHNICAL_DOCUMENTATION.md#19-vox-apps-ecosystem-cross-app-contract).
For `vox-commander` itself, see that same document. For building a new satellite app from scratch, see
[`SATELLITE_APP_GUIDE.md`](SATELLITE_APP_GUIDE.md).

## Vox Notes

Standalone, encrypted on-device notes app (`com.voxapps.notes`, Room + SQLCipher). Voice-created
through Commander (`create`/`read`) or used entirely on its own.

- **Categories** with a coverflow-style picker; **Auto-Merge Categories** asks Commander's LLM hook to
  find and merge near-duplicate categories (e.g. "Shopping" / "Groceries")
- **Note cleanup** — the same LLM hook finds near-duplicate/redundant notes, but unlike category
  merge this only *proposes* groups (kept note + duplicates); the user reviews and taps **Apply
  selected** before anything is deleted — nothing is ever auto-deleted
- Both triggers are available **on-demand** and on a **schedule** (off/daily/weekly/monthly)
- **Scan-to-note** — receives raw OCR text from Vox Vision, sends it through the LLM hook to get a
  clean title/body and a suggested category, then creates the note
- Editor UI: tap a note's title to expand/collapse it in place (collapse via a dedicated chevron
  above the title, separated from the body by a shaded header)
- **Calendar view** (optional, off by default) — a month-paged agenda view (shared `:core:calendar`
  module) instead of the plain chronological list, with a peek into the tail/head of adjacent months
  and a "Today" button; month/weekday names follow the app's own language setting, not the device locale
- **Category color picker** — shared `:core:design` component (also used by Vox Expenses/Vox
  Calendar): a scrollable preset row with a clear ring around the selected swatch, plus a "Custom…"
  entry opening a full-screen color screen (Hue/Saturation/Value sliders, live preview, the same
  presets for a quick pick). Auto-generated presets sit at evenly-spaced hues so they're never visually
  close to each other. The note editor's category coverflow has a trailing "+" entry that opens this
  same picker inline, creating and immediately selecting the new category without leaving the note.
- **Attach photo on scan** (Settings, off by default) — sends the scanned photo to the AI alongside
  the OCR text when Vision provided one and the configured engine supports images; Notes has no
  retry/stub mechanism, so unlike Vox Expenses there's no separate on-retry toggle
- **Attachments** — a collapsible thumbnail strip on every note for extra photos beyond whatever scan
  (if any) created it; tap a thumbnail for a full-screen zoomable view (pan/zoom disabled on the
  thumbnail itself, enabled in the full view), with an inline add/remove. **Scan image retention**
  (Settings, default "on failure") independently controls whether the original scanned photo itself
  survives as an attachment: off, only when Commander's cleanup pass fails outright (so an otherwise
  unusable "Unclear Document" scan becomes a raw note holding just the photo instead of being lost),
  or always
- **Home-screen widget** (Jetpack Glance) — recent notes grouped by day, category-colored rows, tap a
  note to edit it in place, plus Add/Scan actions — reads the same reactive state as the in-app UI, so
  a biometric-locked session shows locked here too
- **Peer-to-peer device sync** (paired from Vox Hub, see [Vox Hub](#vox-hub) below) — genuinely
  bidirectional sync with another phone over NFC + Bluetooth, no cloud; category-scoped, last-write-wins
  on a conflicting edit, deletions propagate via tombstones
- Multi-language UI (English, Romanian, German, French)

## Vox Vision

Standalone document scanner (`com.voxapps.vision`, `com.voxapps.vision.VisionActivity`) — no voice
commands in, only OCR text out.

- **Camera capture** (CameraX) with a live overlay of the detected document bounds
- **Auto-capture** — a throttled `ImageAnalysis` pass runs Otsu-threshold brightness-blob detection
  (deliberately not the stricter quad detection used for the final crop, since a document extending
  past the frame edge can't close into a 4-sided contour) and auto-triggers a capture once the bounds
  are stable; sensitivity is user-configurable (low/medium/high)
- **Edge cropping** — the final captured frame is cropped to the detected document quad via OpenCV
  (`DocumentCropper.kt`), built from source against a vendored OpenCV (see
  [`BUILD_TIME_DEPENDENCIES.md`](BUILD_TIME_DEPENDENCIES.md))
- **On-device OCR** via a vendored, patched PaddleOCR Android SDK (`:vendor:ppocr-sdk`) — no network
  round-trip for text recognition
- Recognized text is cleaned up and titled via Commander's generic LLM hook, then forwarded to Vox
  Notes as a new note (see [Vox Vision's scan-to-note flow](TECHNICAL_DOCUMENTATION.md#vox-vision-ocr-satellite))
- Works fully standalone (its own launcher icon) or as a **pending-request target** launched directly
  by another satellite for a hands-free "scan → auto-submit" flow
- **"Send photo to AI" + "Photo detail for AI"** (Settings, off by default) — opt-in multimodal photo
  attachment for satellites that support it; resolution (Low/Medium/High, 768/1024/1536px) is the only
  control that affects LLM token cost, not JPEG quality
- Multi-language UI (English, Romanian, German, French)

## Vox Expenses

Standalone, encrypted on-device expense tracker (`com.voxapps.expenses`, Room + SQLCipher). Voice-created
through Commander (`create`/`read`), scanned from a receipt via Vox Vision, captured automatically from
bank/payment notifications, or entered by hand.

- **Three capture paths, three prompts** — voice (`ExpenseParsePromptBuilder`), receipt OCR
  (`ExpenseScanCleanupPromptBuilder` — extracts vendor, bank, per-item net/VAT/gross when printed, and
  prioritizes the receipt's own printed total over recomputing it from line items), and notification
  capture (`NotificationExpenseParsePromptBuilder` — a deliberately narrower extraction: title/amount/
  currency/vendor/category plus an `isPayment` triage flag, since notification text isn't guaranteed to
  even be a transaction) — all three route through Commander's generic LLM hook, just with different
  `task` IDs and prompts suited to how much structure each source actually has
- **Notification capture** — an opt-in `NotificationListenerService` inspects notifications only from
  apps the user explicitly allowlists (Settings → Notification capture); a matched notification is a
  **pending suggestion** the user must approve or dismiss unless "Auto-accept" is enabled, in which case
  it's inserted directly (still a normal, editable expense row afterward, and still subject to the
  duplicate-rule engine below). The parsing prompt itself adapts to whichever LLM engine is currently
  active — see **Notification-parse prompt tuning** below. Notifications the listener missed (OS-killed
  process, dropped broadcast) get a "last chance" capture on dismissal, plus a manual "Force-check
  notifications now" button (debounced — a rapid double-tap can't dispatch the same notification twice)
  that bypasses the already-processed guard for whatever's still in the notification shade. The
  source-app allowlist picker is a shared `:core:apppicker` card (full-screen modal with Cancel/Done
  confirmation, search + all/user/system filter) backed by a persisted launcher-apps cache — scanned
  once ever, reloaded from cache on every later launch, with a manual "Rescan Apps" button for when a
  new app is installed (plus, on Honor/MagicOS devices, a warning and an App Info shortcut if the OEM's
  own "get installed apps" permission gate is silently emptying the scanned list despite
  `QUERY_ALL_PACKAGES` being granted, and a deep link into that OEM's own app-launch-management screen —
  independent of stock battery-optimization exemption — since it can otherwise block the listener from
  ever rebinding after the process is killed). The request to Commander is now durably queued rather
  than fire-and-forget — see
  [Durable delivery: the pending-request queue](TECHNICAL_DOCUMENTATION.md#durable-delivery-the-pending-request-queue-voxllmrequestqueue)
  — so a notification is recovered automatically even if Commander was killed/stopped at capture time.
- **Notification-parse prompt tuning** — the notification-capture prompt (`NotificationExpenseParsePromptBuilder`)
  branches on whether Commander's currently-active engine is local or cloud (queried once per capture
  via `VoxCapabilityClient.isLocalEngine`): a local engine gets the few-shot examples and a short
  anti-copy clause that exist specifically to work around a small on-device model's demonstrated
  tendency to leak literal example content into real output (backstopped by a deterministic code-side
  strip regardless of what the model does); a cloud engine, which doesn't share that failure mode, gets
  a shorter, example-free prompt instead of a padded-out copy of the local one.
- **Transaction direction** — every expense is tagged outgoing (money spent) or incoming (money
  received/refunded); Reports splits **Total** (outgoing only) from **Received** instead of netting
  them into one misleading figure. Voice/scan/notification parsing infers direction from the source
  text (e.g. "refund," a bank credit notification) and defaults to outgoing when ambiguous.
- **Duplicate detection** (Settings → Expense cleanup) — a generic, user-configurable rule engine
  (`RuleBasedDuplicateChecker`, shared via `:core:datahygiene` — see
  [Generic duplicate-rule engine](TECHNICAL_DOCUMENTATION.md#generic-duplicate-rule-engine)), modeled on
  email-client filters: any expense field (title, vendor, bank, location, comments, amount, currency,
  category, direction, date/time) can be added to a named rule with its own AND/OR, multiple rules
  combine via one global AND/OR, and each rule independently chooses exact-vs-fuzzy string matching and
  a shared time window (1–15 min). Two default rules are pre-seeded reproducing the app's original
  behavior (same amount+currency+direction+time, plus title OR vendor).
  - **Automatic protection** (what runs on every insert) has four modes: **Off**, **Local** (silently
    merges an obvious rule match), **Local + AI** (local merge plus a small AI-scoped confirmation
    check queued for review), and **AI** (review-only, no local merge). A per-rule "applies
    automatically at save" toggle scopes which rules participate here vs. review-only contexts. The
    AI-scoped recall step for **Local + AI** (both automatic and manual/scheduled) uses the same
    configured rules to decide what's worth showing the AI, respecting your time window and field
    choices; pure **AI** mode has no local component and deliberately never consults the rules at all,
    recalling by a fixed amount/currency/direction match instead.
  - **Manual review** (only shown for Local/Local + AI) — when on, an insert-time local-rule match is
    added to the same pending-review list "Check for duplicates now" produces, instead of merging
    silently, so nothing changes without a look first.
  - This replaced two previously-separate systems (an always-on exact-match checker comparing by
    calendar day, and a 3-checkbox near-duplicate add-on) with the one rule engine above — see the
    technical doc link for the full evaluation model.
- **Data-quality scoring for merges** — when two duplicates merge (silently, or approved from the
  review list), which record's data wins per field is no longer just "whichever arrived first": each
  expense is scored on how it was captured (Manual > Scan > Notification > Voice — manual entry is the
  most trustworthy, voice the most error-prone for proper nouns like vendor names) plus how many
  optional fields it actually has filled in, and a manually-edited record always outranks everything
  else. The review list's "keep" selection defaults to whichever group member scores highest (still
  overridable via the radio buttons) and shows each candidate's capture-source tag alongside its
  total/bank/vendor/date-time, so the default is explainable rather than a black box. Approving a
  review group now backfills the kept row's blank fields from its higher-scoring duplicates before
  deleting them, instead of discarding that data outright.
- **Category color adjacency + merchant category memory** — a freshly auto-created category's color is
  chosen to visibly differ from the most-recently-added expense's category (not just from the aggregate
  palette), and repeatedly correcting the same vendor to the same category (configurable 1×/3×/5×/10×
  streak, off by default) makes that mapping auto-apply to future captures for that vendor, overriding
  whatever the LLM/default would otherwise suggest — see **Category cleanup** below for the streak
  toggle's exact location.
- **Inline category creation** — the category dropdown on the expense-edit screen has a
  "+ New category..." entry that opens the picker described below without leaving the screen; the new
  category is selected immediately on save.
- **Line items & VAT** — optional per-item net/VAT/gross breakdown, shown only when enabled (most
  receipts don't carry this detail); a red warning banner appears if the total doesn't match the sum of
  line items, without blocking editing
- **Currency & exchange rates** — a default currency for new expenses, a separate home currency reports
  convert *into* when expenses mix currencies, and a locale-independent decimal separator setting (comma
  vs. period) so typed amounts round-trip correctly regardless of device locale
- **Spending limits** — per-category/per-period budget alerts, checked by a scheduled `WorkManager` job
  (`SpendingLimitCheckWorker`) and delivered as a system notification
- **Category cleanup** (Settings → Categories) — "Remember merchant categories" toggle + 1×/3×/5×/10×
  threshold chips (see above), plus the same Commander LLM-hook pattern as Vox Notes: auto-merge finds
  and merges near-duplicate categories, on-demand or on a schedule
- **Expense cleanup** (Settings → Expense cleanup) — one tab covering automatic protection and the
  duplicate-rule engine (see **Duplicate detection** above), plus an on-demand **"Check for duplicates
  now"** button and a **scheduled** equivalent, both of which stage matches into the same pending-review
  list regardless of trigger — the Commander LLM-hook pattern, reviewed and approved per group before
  anything merges or deletes
- **Calendar view** (optional, off by default) — same shared `:core:calendar` module as Vox Notes, plus
  bank/vendor filters and an amount ascending/descending sort; sorting by amount isn't chronological, so
  it temporarily disables the calendar view (a dismissible chip restores it)
- **Reports** — Total (outgoing)/Received split (see Transaction direction above) and by-category
  breakdowns, converted into the home currency
- **Category color picker** — shared `:core:design` component (also used by Vox Notes/Vox Calendar):
  a scrollable preset row with a clear ring around the selected swatch, plus a "Custom…" entry opening a
  full-screen color screen (Hue/Saturation/Value sliders, live preview, the same presets for a quick
  pick). Auto-generated presets sit at evenly-spaced hues so they're never visually close to each other.
- **Attach photo on scan / on retry** (Settings, off by default, independent toggles) — sends the
  receipt photo to the AI alongside the OCR text when Vision provided one and the configured engine
  supports images; retry (re-sending already-staged OCR text after a failed parse) is a separate
  toggle since it's a distinct, less frequent path
- **Attachments** — a collapsible thumbnail strip on every expense for extra photos beyond the
  original receipt scan (e.g. a warranty card, a second page); tap a thumbnail for a full-screen
  zoomable view, with an inline add/remove — same shared component as Vox Notes/Vox Calendar
- **Home-screen widget** (Jetpack Glance) — recent expenses grouped by day, category-colored rows,
  tap an expense to edit it in place, plus Add/Scan actions
- **Battery-optimization exemption prompt** — Settings → Notification Capture and first-launch
  onboarding both offer a direct button into the OS's "ignore battery optimizations" dialog, since some
  OEMs' aggressive background-process killers can silently unbind the notification listener otherwise
- **Peer-to-peer device sync** (paired from Vox Hub, see [Vox Hub](#vox-hub) below) — genuinely
  bidirectional sync with another phone over NFC + Bluetooth, no cloud; category-scoped, last-write-wins
  on a conflicting edit, deletions propagate via tombstones
- Multi-language UI (English, Romanian, German, French)

## Vox Calendar

Standalone, encrypted on-device calendar (`com.voxapps.calendar`, Kotlin/AGP namespace
`com.voxapps.calendarapp` to avoid a dex-merge clash with the reused `:core:calendar` module — see its
own doc comment). Voice-created through Commander (`create`/`read`) or used entirely on its own.

- **Year / Month / Week / Day views** behind a collapsible sidebar; Month reuses the shared
  `:core:calendar` engine, Week/Day are a new local hour-of-day grid, Year is 12 compact mini-months
- **Colored, named layers** (e.g. Personal / Work / Moon Calendar) instead of a hierarchical
  category tree — flat layers plus optional flat tags, each layer independently toggleable and
  color-coded via the same shared `:core:design` picker Vox Expenses/Vox Notes use (scrollable
  presets, clear selection ring, "Custom…" full-screen HSV picker with live preview)
- **Natural-language event/task creation via Commander** — "dentist in a week" resolves the date
  entirely through the LLM (no local date-NLU), picks the right layer by name (exact match → fuzzy
  match via `:core:textmatch` → configurable default), and works for both timed events and due-date
  tasks
- **ICS import/export** (Settings → ICS import/export) via the `biweekly` library — spec-correct
  interop with Google Calendar/Thunderbird/Apple Calendar, independent of the Hub JSON backup path
- **Day-summary sheet** — tapping a day shows that day's Calendar entries plus Notes/Expenses inline
  (a day-scoped `OP_READ` extension to the Vox contract), every row styled and colored the same way as
  the home-screen widgets and directly tappable into that record's editor; a Notes/Expenses section is
  omitted entirely (not shown empty) when that app isn't installed, checked locally before ever
  attempting the cross-app fetch. The reverse direction (Notes/Expenses → open Calendar on a date) uses
  a plain explicit-intent extra, not the broadcast bus
- Recurrence is deliberately minimal (none/daily/weekly/monthly/yearly + optional until-date, expanded
  at read time) — no RRULE engine, no per-occurrence materialized rows, editing a series edits the
  whole thing
- **Attachments** — a collapsible thumbnail strip on every entry for extra photos (e.g. a ticket, a
  booking confirmation); tap a thumbnail for a full-screen zoomable view, with an inline add/remove —
  same shared component as Vox Notes/Vox Expenses
- **Home-screen widget** (Jetpack Glance) — upcoming entries grouped by day (today bolded, its
  divider thicker), layer-colored rows with tag chips, tap an entry to edit it in place, plus
  Add/Scan actions
- **Peer-to-peer device sync** (paired from Vox Hub, see [Vox Hub](#vox-hub) below) — genuinely
  bidirectional sync with another phone over NFC + Bluetooth, no cloud; layer-scoped, last-write-wins
  on a conflicting edit, deletions propagate via tombstones
- Multi-language UI (English, Romanian)

## Vox Hub

A lightweight, database-free backup/restore utility (`com.voxapps.hub`) — not a voice-command
satellite (it registers no NLU domain), just an IPC *client* over the same `:core:ipc` contract every
other satellite implements as a server.

- **Zero hardcoded app list** — at launch it calls `VoxAppsDiscovery` to find every installed Vox app
  that advertises `export`/`import` in its manifest meta-data; a new satellite (like Vox Calendar) shows
  up automatically with no Hub-side code change
- **Export** — one card per discovered app with four independent toggles (**Settings**, **Data**, **API
  keys**, **Attachments** — Data/Attachments only shown for apps that actually have record data, e.g.
  not Commander), plus an **All** toggle that sets every toggle for every app at once; bundles every
  selected app's exported JSON — and, when its Attachments toggle is on, that app's photo attachments —
  into one dated zip via `ActivityResultContracts.CreateDocument`. This exact configuration is also
  what **scheduled backups** use (see below) — there's no separate data-selection step for those
- **Import** — reads a previously exported file, previews a per-app record-count summary before
  anything is written, and lets the user deselect individual apps; each target app applies its own
  snapshot-then-replace semantics (existing records for that domain are fully replaced by the import,
  not merged) and re-attaches any bundled photos to the newly-restored records
- **Scheduled backups** — off/daily/weekly/monthly interval (`WorkManager`), configurable retention
  (none/2/5/10/unlimited, with a storage-growth warning at unlimited), a dismissible failure banner
  when a scheduled run doesn't complete, and a past-backups list (capped to 5 visible rows, scrollable)
  with per-backup **Share** and **Restore** actions. Frequency/retention controls are disabled until at
  least one app has something selected in the Export card above — there's nothing to schedule otherwise
- **Peer-to-peer device sync** — a second, genuinely *bidirectional* path alongside export/import's
  one-directional restore: pair two phones over NFC (tap to exchange identity + a session key — no
  Bluetooth PIN dialog), then sync Notes/Calendar/Expenses over Bluetooth Classic, both phones ending up
  with each other's changes, not one overwriting the other. Trigger a sync by tapping again, from a
  manual **Sync now** per paired device, or leave **Auto-sync** on for a background check every 15–240
  minutes (configurable). Per-peer category/layer checklist controls what's included. See
  [`TECHNICAL_DOCUMENTATION.md`'s Peer-to-peer device sync section](TECHNICAL_DOCUMENTATION.md#peer-to-peer-device-sync-op_sync_export--op_sync_merge)
  for the full architecture.
- Holds no local Room database for its own app data — sync's paired-device identities/keys are the one
  exception, kept in `EncryptedSharedPreferences`; everything else it persists itself is UI preference
  (theme, backup schedule)
