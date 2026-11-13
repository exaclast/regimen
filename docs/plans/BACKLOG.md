# Regimen Backlog

The one list of everything still to do. Last reconciled 2026-11-10 against commit `2d089d9` (179 unit tests passing).

| Other documents | What's in them |
|-----------------|----------------|
| `DONE.md` | What has shipped, and what was merged or retired. Old IDs from the audits still resolve there |
| `DECISIONS.md` | Decisions already made and why, plus the ones waiting on you |
| `designs/` | Design notes for larger work. They explain *how*; tasks live here |
| `archive/` | The original plans and audit, unchanged and not in git. Search them by the old ID in the **Was** column for the full write-up |
| `../../AGENTS.md` | How the app works today |

---

## How to use this file

**Adding something.** Add one row in the matching area, using the next free number (next: **T-116**). IDs never change
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

Order matters only where noted. **M3 must finish before M5:** once a stable release exists, database and backup format
changes need real migrations, so anything that reshapes data belongs before it.

| | Milestone | Why | Tasks |
|-|-----------|-----|-------|
| **M1** | **Trust** | Make reminders reliable, protect health data, fix wrong information | T-001–006, T-011–013, T-016, T-019, T-026, T-027, T-038, T-050–053, T-068, T-084 |
| **M2** | **Adaptive** | Tablet, foldable and desktop. UI only, so it can overlap M1 | T-036, T-066, T-067, T-069–074 |
| **M3** | **Foundations** | Data integrity, threading, a sync-ready model, tests | T-014, T-015, T-054, T-055, T-057–065, T-085 |
| **M4** | **Everyday features** | The features that make it worth using daily | T-017, T-018, T-021, T-029, T-030, T-039, T-040, T-044, T-093–096, T-098 |
| **M5** | **Stable release** | First non-prerelease tag | T-056, T-078, T-081, T-087, T-090, T-097, T-100 |
| **M6** | **Sync** | Google Drive sync | T-102 |
| **M7** | **Wear OS** | Doses from the wrist. Later, and D-9 (how the watch gets its data) has to be settled first | T-106–110 |

### Up next

Small, high-value and independent of each other. Suggested order (T-111 to T-113, which reshaped data, are done):

1. **T-011** Dialogs clip on short windows
2. **T-050** Delete label photos
3. **T-001** Warn when reminders can't work
4. **T-002** Don't lose alarms while the phone is off
5. **T-038** Editors lose selections
6. **T-003** / **T-004** Test notifications and demo data touch real alarms
7. **T-026** Scans overwriting the dose
8. **T-068** Invisible "Review" button
9. **T-084** Lint in CI, so all of this stays fixed

---

## Tasks

### Reminders and alarms

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-001 | Today only warns about the in-app toggle. Also warn when notifications are denied, the channel is blocked, exact alarms are denied, or full-screen intents are blocked | P0 | S | M1 | B18, A16 |
| T-002 | Alarms due while the phone is off are lost. On boot, post overdue doses and alert once; includes a snooze that ended while off | P0 | M | M1 | B17, B5 leftover |
| T-003 | Test reminders use the real notification: Take logs a real dose, swiping snoozes real doses. Send a labelled test with no dose actions | P1 | S | M1 | B25 |
| T-004 | Demo data arms real alarms, is available in release builds, and from Settings replaces real data. Limit it to debug or suppress its reminders | P1 | S | M1 | B26, A5 |
| T-005 | (verify) Opening the alarm screen from the notification (`ACTION_SHOW`) may not silence the insistent sound | P1 | S | M1 | B35 |
| T-006 | Pressing Home on the alarm screen leaves it ringing up to 10 minutes with nothing on screen | P2 | S | M1 | Low |
| T-007 | `AlarmRingtonePlayer` leaks a `MediaPlayer` on a failed attempt, and `prepare()` runs on the main thread | P3 | S | — | Low |
| T-008 | Notification channel description still mentions Snooze | P3 | S | — | Low |
| T-009 | Remove legacy notification code: `dose:` tag, `END_ALARM` snooze, `legacyItemKey`, unused `EXTRA_*` | P3 | S | — | O9, roadmap |
| T-010 | Hint that swiping a reminder snoozes it | P3 | S | — | roadmap |

### Dosing and schedules

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-011 | (partial) `LogTimeDialog` clips on short windows: Time squashed, Note gone. Make it scroll (`SiteSelectionDialog` already does, since T-111) | P1 | S | M1 | form factors |
| T-012 | "Take early" has no friction hours ahead of a window, and on past days the main button is "Take now" | P1 | S | M1 | B29 |
| T-013 | A new schedule can start out "Missed": it defaults to today, including windows already over | P1 | S | M1 | B31 |
| T-014 | Adding a window to a schedule back-fills missed doses, because the schedule's start date covers the new window | P1 | M | M3 | B60 |
| T-015 | Editing a schedule rewrites history. Version schedules: end the old one and start a new one on a meaningful edit. Needed before the calendar (T-101). Dose amount, strength and injection details already change by date (`MedicationAdjustment`, T-113); this is what's left (windows, frequency, date range) | P1 | L | M3 | B30 |
| T-016 | (partial) "Other time" shipped, but choosing "Now" while viewing a past day still stamps the current time on that day. Default to "Other time" on past days | P2 | S | M1 | B32 |
| T-017 | Add Pause for a schedule. "Paused" displays but nothing sets it, and a disabled schedule makes the medication appear as As needed. Update `MedicationGroups` too | P2 | M | M4 | B42 |
| T-018 | An every-N-days-from-last-dose schedule stays due every day after its due date until taken | P2 | S | M4 | Low |
| T-019 | Logs for deleted medications show as "Medication" and can't be edited. Load deleted records for names | P2 | S | M1 | B33 |
| T-020 | The "doses from yesterday" notice stays all day and can't be dismissed | P3 | S | — | B1 leftover |
| T-021 | Calm Today: upcoming windows take as much room as the current one. Collapse those after the next | P2 | M | M4 | O10 |
| T-022 | "Take all on time" for a missed window | P3 | S | — | roadmap |
| T-023 | "Other time" for a due scheduled dose accepts any past date, so today's dose can be recorded as taken weeks ago. Limit the range or warn | P3 | S | — | new |
| T-024 | Choose the dose amount when logging an extra dose (always the default today) | P3 | S | — | new |
| T-114 | Reminders and the alarm screen don't say a dose is new; only Today flags the first dose after a titration step | P3 | S | — | T-113 |
| T-115 | "Plan a change" can't set injection details: they change from a date only through the medication editor's "From a date". Fine for now; revisit if planning equipment changes ahead is common | P3 | M | — | T-113 |

### Medication data and label scanning

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-026 | The scan prompt's example is `"defaultDoseAmount": 1.0`, so a scan tends to overwrite the dose with 1 | P1 | S | M1 | B38, A12 |
| T-027 | A record with no package gets a made-up NDC (`<product>-01` or `00000-0000-00`) that can be saved. Use null | P1 | S | M1 | B41 |
| T-029 | Being offline reads as "no matching FDA record". Separate network errors from empty results | P2 | S | M4 | B40 |
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
| T-038 | Selections use `remember` and revert on rotation: schedule windows, weekdays and specific dates; label photo URI and captured photos (sites fixed in T-111). The user can save the wrong schedule without noticing | P1 | S | M1 | B19 |
| T-039 | Closing an editor discards changes without asking | P2 | S | M4 | Low |
| T-040 | Setting a resolved indication back to Active keeps its end date, so it stays under Past | P2 | S | M4 | B36 |
| T-041 | Provider care dates aren't checked for end before start | P3 | S | — | Low |
| T-042 | A number of 309+ digits parses as `Infinity` | P3 | S | — | Low |
| T-043 | Editing a medication resets its created date; rewriting a dose log resets its created date | P3 | S | — | Low |
| T-044 | Window name chips don't set times, so Lunch and Dinner both default to 08:00 and sort wrongly | P2 | S | M4 | B61 |
| T-046 | Specific-date schedules show a raw ISO date | P3 | S | — | Low |
| T-047 | Guided add-medication flow (identify → dose → schedule → limits → details): generic name before brand, short dropdown values, unit suffixes, explain disabled scan buttons | P2 | L | — | U18, F2, P2 |
| T-048 | Validation messages wrap in half-width fields; "Every N Days (Fixed Cycle)" is jargon | P3 | S | — | U19, U20 |
| T-049 | Create an acute indication from the medication editor, pre-filled from the course | P3 | M | — | roadmap |

### Privacy and security

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-050 | Label photos (patient name, prescriber, drug) are never deleted, and multi-photo scanning now creates more. Delete after analysis or cancel, and clear on launch | P0 | S | M1 | B23, A14 |
| T-051 | The alarm screen draws medication details over the lock screen even in discreet mode | P1 | S | M1 | B15 |
| T-052 | Privacy text is inaccurate: the name "stays on your device" but is in every backup and syncs. ML Kit sends telemetry. Auto Backup includes the plaintext database (see D-3) | P1 | S | M1 | B24 |
| T-053 | Passphrases go into saved state via `rememberSaveable`; the recovery code screen has no `FLAG_SECURE` | P1 | S | M1 | B43 |
| T-054 | Recovery code: ✕ and back skip the "I've saved it" check; unlocking with a code adopts that file's passphrase before the restore is confirmed | P2 | S | M3 | B44 |
| T-055 | Harden backup reading: cap PBKDF2 iterations from the header, cap file and decompressed size, clear keys after use, cache `isConfigured`, explain a lost Keystore key | P1 | S | M3 | O7 |
| T-056 | Play policy: justify or replace `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `USE_FULL_SCREEN_INTENT`; complete Data Safety | P2 | M | M5 | O10 |

### Data integrity and sync readiness

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-057 | Backups aren't validated. A window with `hour: 25` crash-loops the app on every launch, `{}` counts as a backup, dangling references pass. Add `validate()` before preview | P0 | M | M3 | B20 |
| T-058 | Undecodable database rows vanish silently and drop out of future backups. Log, count, keep the raw row, and stop the export | P0 | S | M3 | B21 |
| T-059 | `applyBundle` writes settings before the transaction, so a failed restore leaves them out of step | P1 | S | M3 | B22 |
| T-060 | All database and scheduling work runs on the main thread and reloads everything on each tap; receivers don't use `goAsync`. Background dispatcher, batch writes, recent-logs snapshot, coalesced `refresh` | P1 | L | M3 | O1, A17 |
| T-061 | Dose windows are hard-deleted, so a deleted window can return on sync. Soft-delete them | P1 | M | M3 | O6, known gap |
| T-062 | The primary claim and device list aren't stored, so `localChanged` would always be true with two devices | P1 | S | M3 | O6 |
| T-063 | Merge ties don't converge: with equal timestamps each device picks the other's copy. Break ties on something stable | P1 | S | M3 | O6 |
| T-064 | "Delete all data" and "Load demo data" erase tombstones, and demo records use fixed IDs | P1 | S | M3 | O6 |
| T-065 | Wall-clock timestamps decide conflicts. Detect large skew or use a logical clock | P2 | S | M3 | O6 |

### Adaptive and desktop

Measurements and device notes are in `designs/form-factors.md`.

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-066 | Cap content width, centered (600-840 dp), on lists, the medication page, editors and Settings. Up to 1,690 dp wide today | P1 | S | M2 | form factors |
| T-067 | Bottom inset applied twice, leaving an empty band above the nav bar on every tab | P1 | S | M2 | B28 |
| T-068 | The "Review" button on the notice card is nearly invisible where the card is light (seen in dark theme). Use the card's on-container color; check other text buttons on tinted containers | P1 | S | M1 | form factors |
| T-069 | List and detail side by side for Medications, Providers and Indications with Navigation 3 scenes. Threshold above 840 dp (see D-7) | P2 | L | M2 | form factors |
| T-070 | Today as a grid of dose windows in wide windows | P2 | M | M2 | form factors |
| T-071 | Two-column editors and medication page | P2 | M | M2 | form factors |
| T-072 | Compact top bar at compact heights: the large title takes about 40% of a short window | P2 | S | M2 | form factors |
| T-073 | Keyboard: Esc closes editors and goes back, Ctrl+N adds, arrows or `[` `]` change day | P2 | M | M2 | form factors |
| T-074 | Tooltips on icon-only buttons; right-click on a dose row opens its menu | P2 | S | M2 | form factors |
| T-075 | "Log extra dose" covers the last card's buttons until you scroll | P3 | S | — | form factors |
| T-076 | Hinge and posture awareness for tabletop and book. Needs a real device to judge | P3 | M | — | form factors |
| T-077 | Cosmetic: dose menu labels misaligned (only one has an icon); collapsed app bar leaves the rail strip unfilled | P3 | S | — | form factors |

### Accessibility and polish

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-078 | 200% font scale and TalkBack pass | P2 | M | M5 | P2 |
| T-079 | "Take at a different site" missing from the dose card's TalkBack actions | P3 | S | — | Low |
| T-080 | Specific-dates calendar always starts on Sunday; its cells have no TalkBack role or date label | P3 | S | — | Low |
| T-081 | Move UI text to `strings.xml`; nearly all of it is hard-coded | P2 | M | M5 | R3b, O10 |
| T-082 | Search is inconsistent: app bar on Medications, inline box on Indications, none on Providers | P3 | S | — | O10 |
| T-083 | Expressive polish: short nav bar, type scale and `Shapes`, progress ring and haptic on completing the day, motion, newer M3 components, empty-state art, Live Update notifications | P3 | M | — | P2, P3, U26 |

### Build and code quality

| ID | Task | Pri | Size | Goal | Was |
|----|------|-----|------|------|-----|
| T-084 | CI: add `lintDebug` (suppress the one false alarm first); `contents: write` only on the release job; fail instead of publishing unsigned; pass `version_name` via `env`; pin actions to SHAs; drop V1 signing | P1 | S | M1 | O4 |
| T-085 | Test the layers where bugs live: Robolectric for repository and receivers, Compose tests for the take flow and state restoration, instrumented Undo after Replace. `androidTest` is empty | P1 | L | M3 | O5, known gap |
| T-087 | Turn on R8 and resource shrinking; `proguard-rules.pro` is referenced but missing | P2 | M | M5 | O2, A18 |
| T-088 | Baseline Profile | P3 | S | — | O2 |
| T-089 | Reduce recomposition: `RegimenApp` collects all of `uiState` at the root | P3 | M | — | O3 |
| T-090 | Update dependencies, serialization first; Material 3 stable. Remove truly unused ones, but keep `androidx.window` and the adaptive libraries until T-069 and T-076 are settled | P2 | M | M5 | O8, F4 |
| T-091 | Cleanup: dead `is_pcp_sort` query, `KEY_SEEDED_INITIAL`, `InfoGroup`, `sortOrder`, `windowSoftInputMode` on `<application>`; stale names (`DrugBarcodeScanner`, "v3" KDoc, editor "dialog" KDoc) | P3 | S | — | O9, A18 |
| T-092 | Split large files: `MedicationEditorScreen` (1,995 lines), `SettingsScreen` (1,369), `TodayScreen` (1,251) | P3 | M | — | O9 |

### Features

Designs are in `designs/features.md` and `designs/sync-and-backup.md`.

| ID | Task | Pri | Size | Goal | Needs | Was |
|----|------|-----|------|------|-------|-----|
| T-093 | Automatic encrypted local backups | P1 | L | M4 | T-057–059 | phase 3 |
| T-094 | Per-medication dose history (the page shows only 10) | P2 | M | M4 | | roadmap 13, O10 |
| T-095 | Refill and supply tracking | P1 | L | M4 | | roadmap 8 |
| T-096 | Combined daily limits across medications | P1 | M | M4 | | roadmap 9 |
| T-097 | App lock and hidden app preview | P2 | M | M5 | | roadmap 11 |
| T-098 | Share the medication list as text | P2 | M | M4 | | roadmap 12 |
| T-099 | Doctor / EMS view, then PDF | P2 | L | — | T-098 | roadmap 12 |
| T-100 | Home-screen widget and quick log | P2 | M | M5 | T-060 | roadmap 10, P2 |
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
| Snooze across reboot | Repeat reminders off, snooze, reboot, wait | The reminder comes back | B5 |
| As-needed unit | Log an as-needed dose from its card | Log matches the amount and unit on the card | B10 |
| Swipe to snooze | Swipe a reminder on an unlocked phone | Comes back after the snooze time | B16 |
| Other time, pacing | Log an extra dose at another time over a limit | "Take anyway?" reflects that time | 27ae543 |
| Save a dose edit, then Undo | Change time and note, save, Undo | Change applied, then fully reverted | 2d089d9 |
| Extra-dose search | Have more than six medications | Search box appears and filters | 27ae543 |
| Date picker label | TalkBack on a date field | Announces "Choose <label>" (F1; the label is in the code now) | F1 |
| Indication editor | Open it on a phone | The three-way kind selector fits | F3 |
| Split-screen and window resize | Resize through compact, medium, expanded; repeat after T-069 | Layout follows, selection survives | form factors |
| Dark theme on tablet and foldable | Repeat the screen pass | No contrast problems | form factors |
| Passphrase timing | Time the PBKDF2 step (600k iterations) | Well under 1 second | known gap |
| Delete from a detail page | Delete a medication, a provider and an indication from their pages | Goes back once, to the list | T-045 |
| Insulin syringe migration | Install over a build from before `d617fdf` with a syringe description | Type is filled in, text gone | T-113 |

