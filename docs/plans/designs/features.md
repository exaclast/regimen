# Design: Features

Notes for larger features. Tasks, priorities and dependencies are in `../BACKLOG.md`. None of this is started.

---

## Dose steps (T-113)

A schedule has one `doseAmount`, so a titration (isotretinoin 40 → 60 → 80 mg) or a taper means overwriting it, which
rewrites past days, or ending the schedule and starting another, which loses the link between them.

- **Data:** `MedicationSchedule.doseSteps: List<DoseStep(startDateIso, amount, unit, note)>`. The schedule's own amount
  is the dose before the first step. `doseOn(date)` returns the latest step on or before the date. Logs already store the
  amount taken, so past doses are right either way.
- **Read through `doseOn(date)`** everywhere that reads `schedule.doseAmount` today: `DoseEngine` (board items, window
  text, volume), both receivers, `RegimenViewModel`, `RegimenRepository.logDose`. Daily amount limits use the day's amount.
- **Editing:** "Change dose" on the schedule asks for the new amount, an effective date (default today, future allowed)
  and an optional note ("increased at 1-month visit"). Changing the amount in the schedule editor on a schedule that
  already has logged doses offers the same, rather than silently rewriting.
- **Showing it:** a step timeline on the medication page ("40 mg · 60 mg from Dec 1 · 80 mg from Jan 1"). Today and the
  reminder mark the first dose at a new amount ("New dose: 60 mg").
- **Sync:** steps live on the schedule record, so they merge with it. Keep the list sorted by date.
- **Not covered:** a change in how often or which windows (40 mg once a day → twice a day). That's T-015.
- **Tests:** `doseOn` at, before and between steps; overnight windows; a step on the schedule's start date.

## Concentration from strength (T-112)

- Parse the strength into an amount per mL: "200 mg/mL", "100 units/mL", "200 mg/10 mL" (= 20 mg/mL), "400 mg/5 mL",
  "0.5 mg/0.5 mL". The unit is mg, mcg or units. Reuse and test `FdaNdcLookupService.parseConcentrationMgPerMl` logic.
- Drop `InjectableDetails.concentrationMgPerMl`; a database migration moves nothing, since the strength is already there.
- Volume = dose ÷ amount per mL when the dose unit matches (mg/mcg convert to each other; units only to units). Oral
  liquids get volumes too. Prefilled devices still show none.
- The editor shows "20 mg per mL (from strength)" under the strength, or a hint when it can't be read.

## Refill and supply tracking (T-095)

There's no supply count, run-out date or refill info on a medication.

- **Fields on `Medication`:** quantity at the last fill, `lastFilledDateIso`, refills remaining, optional days' supply.
  Defaults keep old data valid.
- **Derive, don't decrement.** Supply left = quantity at fill − doses taken since the fill date. A counter that each device
  decrements would conflict; a derived value merges cleanly.
- **Units.** Start where the supply unit matches the dose unit (tablets, capsules, puffs). For vials use
  `Medication.doseVolumeMl`; for pens count devices. Where there's no safe conversion, ask for a manual "refilled" entry
  instead of guessing.
- **Run-out estimate** from the schedule (as-needed medications use the recent average), shown as "about 9 days left" on
  the medication page and as a chip on Today.
- **Reminder** a few days before running out, one low-priority notification per medication, through
  `DoseNotificationManager.refresh` like every other alarm.
- **Scanner prefill:** add quantity, refills and fill date to the label schema.
- **Tests:** supply arithmetic and run-out dates as pure functions.

## Combined limits across medications (T-096)

Daily limits are per medication, so two products with the same ingredient can each look fine while the total isn't.

- **Data:** store active ingredients and strengths on `Medication` when an FDA match supplies them; allow manual entry for
  OTC products.
- **Limit:** a per-ingredient daily maximum, across every medication that shares the ingredient, counted by the same
  calendar-day rule as `DoseEngine.limitDayOf`.
- **Surface it as pacing:** the existing "Take anyway?" flow, notifications and the alarm screen (extend
  `PacingEvaluation.warningText`).
- **Don't guess:** if a strength can't be parsed, skip that ingredient. Tests: totals across medications, units, day
  boundary.

## Dose history (T-094)

`Route.MedicationHistory(id)`: every logged dose grouped by day, filterable by taken and skipped, tapping a row opens the
existing `DoseLogEditor`. Older logs should come from an on-demand query once T-060 narrows the in-memory snapshot. The
calendar reuses it when a day is tapped.

## Adherence calendar (T-101)

- A month grid with a per-day ring or color (taken / scheduled; skipped and missed distinguished). Tap a day to open it on
  Today.
- Per-medication filter; streaks; 7- and 30-day percentages.
- Build on `DoseEngine.buildDailyBoard`. Respect schedule date ranges; logged doses always count.
- **Depends on schedule versions (T-015),** or editing a schedule rewrites past days.

## Share list and doctor / EMS view (T-098, T-099)

- **First:** text through the system share sheet: name, strength, schedule, last dose, prescriber, indication, and patient
  name from Settings. The user chooses what's included.
- **Then:** a PDF (`PdfDocument`) and a read-only, high-contrast screen for a clinician or EMS.
- **To decide:** what's safe to show from a locked phone.

## Home-screen widget and quick log (T-100)

- A Glance widget with the next due window and as-needed medications with a Log button.
- Receivers never call `startActivity`. The button logs through the repository and then `refresh`. If pacing says no, it
  opens the app, where the limit is explained.
- A quick-settings tile is a lighter variant for one chosen medication.

## App lock and hidden preview (T-097)

- Biometric or device-credential prompt on resume after a timeout. A per-device setting, not synced.
- Hide the app preview in recents (`FLAG_SECURE` or `setRecentsScreenshotEnabled(false)`).

---

## Plain-language schedules (T-104)

On devices with Gemini Nano, type "one tablet twice a day with food for 10 days, starting tonight" on the schedule editor
and get the fields filled in. Creation only to start; editing an existing schedule is much harder.

**Fit:** `DrugBarcodeScanner` already checks `FeatureStatus` and calls the Prompt API; `DrugLabelParser` already builds a
prompt and validates JSON. The target is `MedicationSchedule`.

**Rules**

1. **The model never saves.** It fills the editor, and the user reviews and taps Save.
2. **Code makes the decisions the model shouldn't.** Resolve window names against the user's real windows. Work out relative
   dates in code from the device clock. Run the result through the editor's validation and reject what it can't represent.
3. **Ask, don't guess.** If a window, dose or duration is missing or ambiguous, leave the field empty and say so.
4. **Mark filled fields** with the "needs review" state (T-030).
5. **Same gating as label scanning.** Text stays on the device.
6. **Keep medication facts out of it.** It sets the schedule only.

**Tests:** a table of phrases → expected schedule, plus cases that must come back incomplete.

**Prompt setup:** its own prompt taking the user's windows and the clock. If that duplicates the label parser's scaffolding,
share it afterward (T-105).
