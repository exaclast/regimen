# Regimen Backlog

The one list of everything still to do. Last reconciled 2026-11-15 against `"Complete M5 (v1.0): auto backups, adaptive layout, and release gates"` on `main`.

| Other documents | What's in them |
|-----------------|----------------|
| `DONE.md` | What has shipped, and what was merged or retired. Old IDs from the audits still resolve there |
| `DECISIONS.md` | Decisions already made and why, plus the ones waiting on you |
| `designs/` | Design notes for larger work. They explain *how*; tasks live here |
| `archive/` | The original plans and audit, unchanged and not in git. Search them by the old ID in the **Was** column for the full write-up |
| `../../AGENTS.md` | How the app works today |

---

## How to use this file

**Adding something.** Add one row in the matching area, using the next free number (next: **T-124**). IDs never change
or get reused. One sentence is enough; anything longer goes in `designs/`. An audit or device pass adds rows here, and its
raw report goes in `archive/`.

**Triage.** Give it a priority and a size. If it belongs in the current milestone, say so; otherwise leave Goal as `—`.
Don't add it to **Up next** unless it displaces something.

| Priority | Meaning |
|----------|---------|
| **P0** | Can cause a wrong, missed or double dose, lost data, or exposed health data |
| **P1** | Breaks trust or daily use, or wrong information shown |
| **P2** | Real problem with a workaround, or a feature with clear value |
| **P3** | Minor, cosmetic, or speculative |

**Size:** S = under a day · M = a few days · L = a week or more, needs a design note.

**State.** A row is open unless it starts with one of these: **(partial)** some of it shipped · **(verify)** may be
fine, reproduce before fixing.

**Finishing.** Delete the row, add one line to the top of `DONE.md` with the commit and the proof (test, device, or
"not run"), and put anything left over back as a new row. If it needs a person with a device to confirm, add it to **Device checks**.

**Keeping it honest.** Mention the T-number in the commit body. When a milestone ends, re-check its rows against the code
and update the "last reconciled" line above.

---

## Milestones

Order matters only where noted. **v1.0 means the data format is frozen:** once a stable release exists, database and
backup format changes need real migrations, so anything that reshapes data (M3) ships before it, in v0.9, which is the
last release that may break the format. v1.0 is v0.9 plus the release gates and a few small wins (M5). Google Drive sync
is not part of the v1.0 promise.

| | Milestone | Release | Why | Tasks |
|-|-----------|---------|-----|-------|
| **M1** | **Trust** | v0.8 (done) | Make reminders reliable, protect health data, fix wrong information, and the two P0 data-loss bugs | T-001–006, T-011–013, T-016, T-019, T-026, T-027, T-038, T-050–053, T-057, T-058, T-068, T-084, T-122 |
| **M3** | **Foundations** | v0.9 (done) | Everything that reshapes stored data, plus the safety net. Scoped: T-060 is the receivers and the worst reload path, T-085 is Robolectric only | T-014, T-015, T-054, T-055, T-059–064, T-085 |
| **M5** | **Stable release** | v1.0 (done) | Release gates and cheap wins on top of v0.9, including automatic local backups and the two width fixes that keep tablets from looking broken | T-029, T-039, T-040, T-044, T-056, T-066, T-072, T-078, T-087, T-090, T-093 |
| **M2** | **Adaptive** | 1.x | Tablet, foldable and desktop. UI only, so it can overlap anything. T-066 and T-072 shipped in v1.0 | T-036, T-069–071, T-073, T-074 |
| **M4** | **Everyday features** | 1.x | The features that make it worth using daily; app lock and the widget are the first candidates | T-017, T-018, T-021, T-030, T-094–097, T-100 |
| **M6** | **Sync** | 1.x | Google Drive sync | T-065, T-102 |
| **M7** | **Wear OS** | 1.x | Doses from the wrist. Later, and D-9 (how the watch gets its data) has to be settled first | T-106–110 |

### Up next

v1.0 (M5) is ready for release testing; next candidates for 1.x:

1. **M4 (Everyday features):** App lock (**T-097**), home-screen widget (**T-100**), refill and supply tracking (**T-095**), combined daily limits (**T-096**)
2. **M2 (Adaptive):** List-detail two-pane scenes (**T-069**), desktop/wide Today grid (**T-070**), two-column editors (**T-071**), keyboard shortcuts and tooltips (**T-073**, **T-074**)
3. **M6 (Sync):** Google Drive sync (**T-102**) and clock skew handling (**T-065**)

---

## Tasks

### Reminders and alarms

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-007 | `AlarmRingtonePlayer` leaks a `MediaPlayer` on a failed attempt, and `prepare()` runs on the main thread | P3 | S | — | Low |
| T-008 | Notification channel description still mentions Snooze | P3 | S | — | Low |
| T-009 | Remove legacy notification code: `dose:` tag, `END_ALARM` snooze, `legacyItemKey`, unused `EXTRA_*` | P3 | S | — | O9, roadmap |
| T-010 | Hint that swiping a reminder snoozes it | P3 | S | — | roadmap |

### Dosing and schedules

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-017 | Add Pause for a schedule. "Paused" displays but nothing sets it, and a disabled schedule makes the medication appear as As needed. Update `MedicationGroups` too | P2 | M | M4 | B42 |
| T-018 | An every-N-days-from-last-dose schedule stays due every day after its due date until taken | P2 | S | M4 | Low |
| T-020 | The "doses from yesterday" notice stays all day and can't be dismissed | P3 | S | — | B1 leftover |
| T-021 | Calm Today: upcoming windows take as much room as the current one. Collapse those after the next | P2 | M | M4 | O10 |
| T-022 | "Take all on time" for a missed window | P3 | S | — | roadmap |
| T-023 | "Other time" for a due scheduled dose accepts any past date, so today's dose can be recorded as taken weeks ago. Limit the range or warn | P3 | S | — | new |
| T-024 | Choose the dose amount when logging an extra dose (always the default today) | P3 | S | — | new |
| T-114 | Reminders and the alarm screen don't say a dose is new; only Today flags the first dose after a titration step | P3 | S | — | T-113 |
| T-120 | Localization left-overs: a lint check for hardcoded Compose text (Android Lint only checks XML). Still English by design: the default dose window names and site presets (stored data), the demo data, the backup file name, the "See package" strength placeholder, the model prompt, and ISO dates in `ScheduleText` ("On 2026-11-04") | P3 | S | — | — |
| T-115 | "Plan a change" can't set injection details: they change from a date only through the medication editor's "From a date". Fine for now; revisit if planning equipment changes ahead is common | P3 | M | — | T-113 |

### Medication data and label scanning

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-030 | "Needs review" state for fields filled by AI or FDA data, since they directly affect dosing. T-104 reuses it | P2 | M | M4 | O10 |
| T-031 | Sanitizer: patient names of 4+ letters still match inside longer words (Mann → Mannitol) | P3 | S | — | B39 leftover |
| T-032 | GS1 expiry day `00` becomes the 28th, and invalid days like Feb 31 pass through | P3 | S | — | Low |
| T-033 | Bare 10-digit NDCs from labels are assumed 4-4-2, though `candidateNdc11s` handles all three layouts | P3 | S | — | Low |
| T-034 | "Match FDA" stays enabled during a lookup, so requests can stack | P3 | S | — | Low |
| T-035 | FDA match dialog: collapse identical multi-salt strengths ("4 × 2.5 mg"). Don't sum them | P3 | S | — | roadmap |
| T-036 | Scan buttons on desktop: untried. Decide what camera and AICore should do there; a file picker suits a label photo | P2 | S | M2 | form factors |
| T-037 | OCR quality on wrapped labels: words split across photos still produce wrong names. Collect more cases before changing anything; no hard-coded parsing | P3 | M | — | new |

### Editors and forms

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-041 | Provider care dates aren't checked for end before start | P3 | S | — | Low |
| T-042 | A number of 309+ digits parses as `Infinity` | P3 | S | — | Low |
| T-043 | Editing a medication resets its created date; rewriting a dose log resets its created date | P3 | S | — | Low |
| T-046 | Specific-date schedules show a raw ISO date | P3 | S | — | Low |
| T-047 | Guided add-medication flow (identify → dose → schedule → limits → details): generic name before brand, short dropdown values, unit suffixes, explain disabled scan buttons | P2 | L | — | U18, F2, P2 |
| T-048 | Validation messages wrap in half-width fields; "Every N Days (Fixed Cycle)" is jargon | P3 | S | — | U19, U20 |
| T-049 | Create an acute indication from the medication editor, pre-filled from the course | P3 | M | — | roadmap |

### Data integrity and sync readiness

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-121 | The rest of the threading work: a background dispatcher for all database and scheduling work, batched writes, a recent-logs snapshot, coalesced `refresh` | P2 | L | — | split from T-060 |
| T-065 | Wall-clock timestamps decide conflicts. Detect large skew or use a logical clock | P2 | S | M6 | O6 |

### Adaptive and desktop

Measurements and device notes are in `designs/form-factors.md`.

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-069 | List and detail side by side for Medications, Providers and Indications with Navigation 3 scenes. Threshold above 840 dp (see D-7) | P2 | L | M2 | form factors |
| T-070 | Today as a grid of dose windows in wide windows | P2 | M | M2 | form factors |
| T-071 | Two-column editors and medication page | P2 | M | M2 | form factors |
| T-073 | Keyboard: Esc closes editors and goes back, Ctrl+N adds, arrows or `[` `]` change day | P2 | M | M2 | form factors |
| T-074 | Tooltips on icon-only buttons; right-click on a dose row opens its menu | P2 | S | M2 | form factors |
| T-075 | "Log extra dose" covers the last card's buttons until you scroll | P3 | S | — | form factors |
| T-076 | Hinge and posture awareness for tabletop and book. Needs a real device to judge | P3 | M | — | form factors |
| T-077 | Cosmetic: dose menu labels misaligned (only one has an icon); collapsed app bar leaves the rail strip unfilled | P3 | S | — | form factors |

### Accessibility and polish

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-080 | (partial) Specific-dates calendar always starts on Sunday (TalkBack role and full date label shipped in v1.0) | P3 | S | — | Low |
| T-082 | Search is inconsistent: app bar on Medications, inline box on Indications, none on Providers | P3 | S | — | O10 |
| T-083 | Expressive polish: short nav bar, type scale and `Shapes`, progress ring and haptic on completing the day, motion, newer M3 components, empty-state art, Live Update notifications | P3 | M | — | P2, P3, U26 |

### Build and code quality

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-123 | Compose UI tests for the take flow and state restoration, plus instrumented Undo after Replace (`androidTest` is empty; Robolectric repository and receiver tests shipped in T-085) | P2 | L | — | split from T-085 |
| T-088 | Baseline Profile | P3 | S | — | O2 |
| T-089 | Reduce recomposition: `RegimenApp` collects all of `uiState` at the root | P3 | M | — | O3 |
| T-091 | Cleanup: dead `is_pcp_sort` query, `KEY_SEEDED_INITIAL`, `InfoGroup`, `sortOrder`, `windowSoftInputMode` on `<application>`; stale names (`DrugBarcodeScanner`, "v3" KDoc, editor "dialog" KDoc) | P3 | S | — | O9, A18 |
| T-092 | Split large files: `MedicationEditorScreen` (1,995 lines), `SettingsScreen` (1,369), `TodayScreen` (1,251) | P3 | M | — | O9 |

### Features

Designs are in `designs/features.md` and `designs/sync-and-backup.md`.

| ID | Task | Pri | Size | Goal | Needs | Was |
|----|------|-----|------|------|-------|-----|
| T-094 | Per-medication dose history (the page shows only 10) | P2 | M | M4 | | roadmap 13, O10 |
| T-095 | Refill and supply tracking | P1 | L | M4 | | roadmap 8 |
| T-096 | Combined daily limits across medications | P1 | M | M4 | | roadmap 9 |
| T-097 | App lock and hidden app preview | P2 | M | M4 | | roadmap 11 |
| T-099 | PDF (or print) of the health summary for appointments. The on-screen summary and its text share already exist | P3 | M | — | | roadmap 12 |
| T-100 | Home-screen widget and quick log | P2 | M | M4 | T-060 | roadmap 10, P2 |
| T-101 | Adherence calendar | P2 | L | — | T-015, T-094 | phase 6 |
| T-102 | Google Drive sync | P2 | L | M6 | T-057–065, T-093 | phase 4 |
| T-103 | Second sync destination (WebDAV / self-hosted) | P3 | M | — | T-102 | phase 5 |
| T-104 | Plain-language schedule creation with Gemini Nano | P3 | L | — | T-014, T-015, T-030, T-057 | O11 |
| T-105 | Share Gemini Nano scaffolding between label and schedule parsing, if T-104 duplicates it | P3 | M | — | T-104 | O11 |

### Wear OS

Options and constraints are in `designs/wear-os.md`.

| ID | Task | Pri | Size | Goal | Needs | Was |
|----|------|-----|------|------|-------|-----|
| T-106 | Find out what a paired watch already gets: how reminders look there, whether Take and Skip work from the watch, and what happens to swipe-to-snooze on an ongoing notification. No code; a device check first | P2 | S | M7 | | new |
| T-107 | Move the pure dose logic (`model`, `domain`, most of `sync`) into a shared module a watch app can use | P2 | L | M7 | D-9, T-060 | new |
| T-108 | Watch app: today's doses with Take, Skip and Snooze | P2 | L | M7 | T-107, T-110 | new |
| T-109 | Tile or widget and a complication for the next dose | P3 | M | M7 | T-108 | new |
| T-110 | Phone and watch data path: Data Layer for a companion watch app, or Drive sync for a standalone one | P2 | L | M7 | D-9, T-062–065 | new |

---

## Device checks

Shipped, but never confirmed on a device. Clear these opportunistically; if one fails, add a task.

| What | Steps | Expected | From |
|------|-------|----------|------|
| Snooze with the app killed | While a window is open, snooze, leave the app, `adb shell am kill com.exaclast.regimen`, wait | The reminder alerts with sound | B6 |
| Alert after boot | With a window open (or just closed) and a dose unlogged, reboot the phone | One alert for that window after boot; a snooze that ended while off alerts too | T-002 |
| Allergy links and narrow phones | Link an antihistamine to an allergy; open the Type selector on a 360 dp phone | The medication editor offers the allergy under its "Drug/Food/Environmental allergies" heading; the Type chips wrap cleanly with five types. Also add an Intolerance (drug only, e.g. Metformin) and check it lists beside drug allergies, labelled "Intolerance", with no allergen-type selector. (Create, list, detail and the Suspected section were checked on the Fold emulator before the Intolerance type was added, T-116/T-117.) | T-117 |
| Secondary indication | Edit an indication, pick another under "Secondary to", save | The list line says "Secondary to X"; X's page lists it under "Secondary conditions"; the health summary line says "secondary to X" | Secondary indications |
| Suspected, switched off | Edit a suspected indication and turn Suspected off | It moves from "Suspected" back under its kind | T-116 |
| Snooze across reboot | Repeat reminders off, snooze, reboot, wait | The reminder comes back | B5 |
| As-needed unit | Log an as-needed dose from its card | Log matches the amount and unit on the card | B10 |
| Swipe to snooze | Swipe a reminder on an unlocked phone | Comes back after the snooze time | B16 |
| Other time, pacing | Log an extra dose at another time over a limit | "Take anyway?" reflects that time | 27ae543 |
| Save a dose edit, then Undo | Change time and note, save, Undo | Change applied, then fully reverted | 2d089d9 |
| Extra-dose search | Have more than six medications | Search box appears and filters | 27ae543 |
| Date picker label | TalkBack on a date field | Announces "Choose <label>" (F1; the label is in the code now) | F1 |
| Indication editor | Open it on a phone | The three-way kind selector fits | F3 |
| Reminder blocked notice | Deny notifications (or block a channel, exact alarms, full-screen) in system settings, open Today | A "Fix" notice names the problem and opens the right settings page; it goes away on return once fixed | T-001 |
| Label photo cleanup | Take photos, read the label (or Discard), then check `cache/label_scans` | Folder is gone; also after killing the app mid-scan and relaunching | T-050 |
| Selections survive rotation | In a schedule editor pick windows, weekdays, specific dates; rotate or fold/unfold. Also take a label photo, rotate before "Read label" | Selections and photos are still there | T-038 |
| Log-time dialog, short window | Open "Log extra dose" > Other time in landscape or split screen | The dialog scrolls; Time and Note reachable | T-011 |
| Split-screen and window resize | Resize through compact, medium, expanded; repeat after T-069 | Layout follows, selection survives | form factors |
| Dark theme on tablet and foldable | Repeat the screen pass | No contrast problems | form factors |
| Passphrase timing | Time the PBKDF2 step (600k iterations) | Well under 1 second | known gap |
| Delete from a detail page | Delete a medication, a provider and an indication from their pages | Goes back once, to the list | T-045 |
| Plan a change with a strength | Plan a change, type a strength | The NDC field's hint says it will be blank from that date | T-113 |
| Insulin syringe migration | Install over a build from before `d617fdf` with a syringe description | Type is filled in, text gone | T-113 |
| Schedule versioning & same-day window guard | Edit a schedule with logs using "Change from date"; also add a window earlier today in Fix mode | Old schedule ends before effective date and collapses under "Past schedules"; newly added earlier window today isn't back-filled as missed | T-014, T-015 |
| Recovery code close & deferred adoption | Open recovery code and tap ✕/Back before confirming; also unlock a backup with a recovery code and cancel restore | Prompt asks before discarding unsaved code; canceling restore keeps the device's existing passphrase | T-054 |
| Automatic encrypted backups | Set backup passphrase, enable Automatic encrypted backups in Settings → Backup, pick a SAF folder, tap "Back up now", then change data and verify scheduled run / retention | `.regimen` encrypted file is written to the folder with today's stamp and old backups are pruned per retention | T-093 |

