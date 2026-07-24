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
  it's inserted directly (still a normal, editable expense row afterward). Duplicate-entry detection
  (same title/amount/currency/vendor/bank/location/comments/category, compared by calendar day rather
  than exact instant so a retried notification-capture doesn't create a second row) skips a repeat
  insert. Notifications the listener missed (OS-killed process, dropped broadcast) get a "last chance"
  capture on dismissal, plus a manual "Force-check notifications now" button (debounced — a rapid
  double-tap can't dispatch the same notification twice) that bypasses the already-processed guard for
  whatever's still in the notification shade. The source-app allowlist picker is a shared
  `:core:apppicker` card (search + all/user/system filter) backed by a persisted launcher-apps cache —
  scanned once ever, reloaded from cache on every later launch, with a manual "Rescan Apps" button for
  when a new app is installed. The request to Commander is now durably queued rather than
  fire-and-forget — see
  [Durable delivery: the pending-request queue](TECHNICAL_DOCUMENTATION.md#durable-delivery-the-pending-request-queue-voxllmrequestqueue)
  — so a notification is recovered automatically even if Commander was killed/stopped at capture time.
- **Transaction direction** — every expense is tagged outgoing (money spent) or incoming (money
  received/refunded); Reports splits **Total** (outgoing only) from **Received** instead of netting
  them into one misleading figure. Voice/scan/notification parsing infers direction from the source
  text (e.g. "refund," a bank credit notification) and defaults to outgoing when ambiguous.
- **Near-duplicate detection** (Settings → Expense cleanup → Direct database matching, off by default,
  separate from the AI-based cleanup below) — a same-day insert whose fields closely match an existing
  row gets merged into it instead of creating a second entry, in **Exact** or **Fuzzy** match mode, with
  a configurable time window (1–15 min) for how close in time two entries must be to be compared at all.
  Deliberately not the LLM-hook cleanup path — this runs entirely locally, synchronously, on every
  insert.
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
- **Expense cleanup** (Settings → Expense cleanup) — two independent cards: **AI-based cleanup** (the
  Commander LLM-hook pattern — *proposes* duplicate-expense groups for the user to review before
  anything is deleted, on-demand or on a schedule) and **Direct database matching** (the
  near-duplicate-detection toggle above, entirely local)
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
- **Export** — per-app `settings`/`data`/`both` scope selection, bundles every selected app's exported
  JSON into one dated file via `ActivityResultContracts.CreateDocument`
- **Import** — reads a previously exported file, previews a per-app record-count summary before
  anything is written, and lets the user deselect individual apps; each target app applies its own
  snapshot-then-replace semantics (existing records for that domain are fully replaced by the import,
  not merged)
- **Scheduled backups** — off/daily/weekly/monthly interval (`WorkManager`), configurable retention
  (none/2/5/10/unlimited, with a storage-growth warning at unlimited), a dismissible failure banner
  when a scheduled run doesn't complete, and a past-backups list (capped to 5 visible rows, scrollable)
  with per-backup **Share** and **Restore** actions
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
