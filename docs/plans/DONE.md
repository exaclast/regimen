# Regimen: Done and Retired

What has shipped, and what was merged or dropped. Open work is in `BACKLOG.md`; reasons are in `DECISIONS.md`.

**Adding an entry.** When a task is finished, delete its row from `BACKLOG.md` and add one row at the top of **Shipped**:
the T-number (or old ID), what changed, the commit, and the proof (tests, device, or "not run"). Anything left over goes
back into the backlog as a new task. Old audit and plan IDs (A, B, O, R, U) stay listed so searches in `archive/` still
resolve.

---

## Shipped

Newest first.

- **T-002** (see this session's commit): after boot, `alertOverdueAfterBoot` alerts once per window whose latest occurrence still has doses needing the user, open or closed, including snoozes that ended while off. Leftover: an unlogged dose from an older occurrence (not the latest) isn't re-alerted. Proof: builds, unit tests pass; device check added.

- **T-038** (uncommitted): schedule windows, weekdays, specific dates and the label photo URI, captured photos and their dialog now survive rotation (`rememberSavableList`, `rememberSaveable`). Proof: unit tests pass; device check added.

- **T-001, T-011, T-050** (uncommitted): Today warns when notifications are denied, a channel is blocked, exact alarms are denied or full-screen intents are blocked (`DoseNotificationManager.reminderProblem`); `LogTimeDialog` scrolls; label photos are deleted after analysis, on Discard and on a fresh launch. Proof: unit tests pass; device checks added.

| Item | Commit | Proof |
|------|--------|-------|
| T-113 follow-up: the NDC is part of a dated change. A change to the strength or the vial or device size clears it from that date (a different product has a different NDC); it stays blank until the FDA lookup fills it in, and filling in a blank NDC is a silent correction. "Plan a change" has an NDC field and says when it will clear; the change dialog explains it | "Clear the NDC when a change alters the strength or package" | `AdjustmentsTest` (7 more), 213 tests; Pixel: editing Lantus' strength showed the NDC note, the change listed "NDC cleared", and the Details NDC row went. The Plan screen's hint is still in Device checks |
| The NDC fields use the numeric keyboard and keep only digits and hyphens (`NumberInput.filterNdc`), so a pasted "NDC 00517-0031-25" or stray letters and punctuation never reach the lookup. Both the medication editor and "Plan a change" | "Limit the NDC field to digits and hyphens" | `NumberInputTest`, 214 tests; Pixel: typed "NDC 00517-0031-25abc.," and got `00517-0031-25` |
| T-113 Dated changes (`MedicationAdjustment`): dose per schedule, default dose, strength and injection details change from a date, so titrations and tapers keep their history and can be planned. Editors show today's values and ask "From a date" or "Correction" when logged doses exist. Medication page lists Changes and the next step; "Plan a change" screen; Today flags "New dose from today". Syringe type with size chips (U-100 / U-500 insulin, tuberculin, Luer-lock, Luer-slip, other), attached needles hide the draw needle, ISO hub colors by gauge; database v5 infers the type from old text. Demo data gains an isotretinoin titration | "Add dated medication adjustments and syringe types" | `AdjustmentsTest` (9), `SyringeMigrationTest` (3), 206 tests; Pixel: titration on Today and the medication page, change-or-correction dialog, plan, delete and Undo, syringe types and hub color |
| T-045 Deleting from a detail page went back twice: the deleted screen's own "record is gone" check fired while it animated out. Guards now use `Navigator.backFrom`, which only goes back if that screen is on top | same commit | Pixel: deleting a change returns to the medication page; medication, provider and indication deletes are in Device checks |
| T-112 No stored concentration: the amount per mL is worked out from the strength (`Concentration.parse`: mg, mcg, g, units, "200 mg/10 mL", "per 5 mL"; combinations give none). Volumes for oral liquids too, and units convert by the real strength instead of assuming U-100. With T-025 (insulin read "100 mg/mL"); FDA results for units-per-mL injectables default to units | "Work out concentration from the strength" | `ConcentrationTest` (7), 194 tests; Pixel with demo data: Lantus 0.2 mL on Today, the editor hint, the draw volume |
| T-111 Sites are Off / Record / Rotate on every form with sites; topicals default to Off. With T-028 (default sites from route and form), the rotation-sites part of T-038, and a scrolling site picker (part of T-011). Database v4 migrates the old switch | `2e0ab7e` | `SiteModeMigrationTest`, 5 new `DoseEngineTest` cases, 187 tests; editor checked on the Pixel (Tablet: no card; Topical: Off, then Record and Rotate); with T-112, Lantus switched to Record shows its last site with a pin and an unnumbered list tagged "Last used" |
| T-086 Debug builds use their own application id (`com.exaclast.regimen.debug`, labelled "Regimen Debug") | "Add debug application id and track plans" | APK checked with `aapt2`; 179 tests; not yet installed on a device |
| Dose editing (time, amount, site, note), notes when logging, time choice on the medication page | `2d089d9` | `DoseLogEditsTest`, back-dated pacing test; phone; laptop |
| Medication grouping chips, provider sections, "Log extra dose", "Now / Other time" (most of B32) | `27ae543` | `MedicationGroupsTest`; phone |
| OCR label scanning, Conditions → Indications, Preventive kind, many-to-many links | `315ccfb` | Migration and timeline tests; phone |
| NDC overrides a strength read from a label | `56ea60b` | `FdaNdcLookupServiceTest` |
| B1, B2, B7–B9, B11, B12; A6, A8, A9, A11, A13, A15 | `158e553` and earlier | Tests; phone |
| B3–B6, B10, B13, B14, B16, B27, B39; `minSdk` 35 | `62251b7` | Tests; some device checks still open (see Device checks) |
| Prescriber → Provider, acute and chronic conditions | `15a231b` | Tests; phone |
| Backup format, three-way merge, encryption, export and import (phases 1–2) | `1c72744` | `BundleMergerTest`, `BundleCryptoTest`, `BackupFilesTest` |
| R1–R17 (Expressive theme, Navigation 3, top bars, Today timeline, Undo, pacing text, lists, Settings, accessibility actions); U1–U17, U21–U25 | `5a09267` | Emulator at about 443 dp and 360 dp |
| A1–A4 (A7): dose matching, numeric strengths, snooze across midnight, strict input | `d3bd85f` | Tests; phone |

## Merged or retired

Nothing was lost; each item points to where it went.

| Item | Outcome |
|------|---------|
| Legacy notification code (O9 and roadmap) | One task, T-009 |
| Soft-delete windows (O6 and known gaps) | One task, T-061 |
| Widget (2026-11-03 P2 and roadmap 10) | One task, T-100 |
| `strings.xml` (R3b, O10, roadmap) | One task, T-081 |
| Dose history (O10 and roadmap 13) | One task, T-094; the calendar builds on it |
| Recovery-code `FLAG_SECURE` and app-wide preview hiding | Separate on purpose: T-053 is a small fix now, T-097 is the feature |
| B32 backfilled doses | Narrowed to T-016 now that "Other time" exists |
| A14 PHI | Only the photos remain (T-050); clipboard, FDA consent and sample names are gone |
| "Log a dose" FAB, one notification per window, export via file picker, patient name save-on-blur | Shipped |
| Material 3 "stay on 1.4 until P3" | Superseded: pinned to `1.5.0-beta02` |
| B16 lock-screen swipe, B12 vial volume note | Platform limit and intended behavior; no task |
| Tracking which drugs AICore refuses | Moot: only text reaches the model now |
| "Bottom bar → Today · Medications · History" | Still open as a question, D-5 |
