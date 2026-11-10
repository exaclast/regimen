# Project Instructions & Conventions (`AGENTS.md`)

This repository is **Regimen**, an Android medication and dose-tracking app built with Kotlin, Jetpack Compose, Material 3, and on-device ML Kit / Gemini Nano label parsing.

---

## 1. Build, Test & Device Commands

- **Run Unit Tests**:
  ```bash
  ./gradlew testDebugUnitTest
  ```
- **Assemble Debug APK**:
  ```bash
  ./gradlew assembleDebug
  ```
- **Install / Sideload via ADB**:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Launch MainActivity**:
  ```bash
  adb shell am start -n com.exaclast.regimen/.MainActivity
  ```
- **Clean Restart App on Device**:
  ```bash
  adb shell am force-stop com.exaclast.regimen && adb shell am start -n com.exaclast.regimen/.MainActivity
  ```

> **Environment Note**: Java 17 is configured via SDKMAN (`/Users/cbrown/.sdkman/candidates/java/current`). When running in sandboxed agent sessions, elevate sandbox permissions for Gradle and ADB socket access.

---

## 2. Git & Commit Conventions

- **Subject Line**:
  - Imperative mood, present tense (e.g. `Add customizable dose windows...` instead of `Added...`).
  - Keep under 72 characters.
  - Capitalize first word; no trailing period.
- **Body**:
  - Separate from subject with a blank line.
  - Use bullet points to describe specific component changes and rationales.
- **Verification Rule**:
  - Always verify `./gradlew testDebugUnitTest` compiles and passes before creating commits.
  - Never push broken code to `main`.
  - Only commit and push when explicitly requested by the user.

---

## 3. Architecture & Code Style

- **UI Framework**: Jetpack Compose with **Material 3 Expressive** (`material3` pinned to `1.5.0-beta02` above the BOM, which requires `compileSdk` 37 and AGP ≥ 9.1) and adaptive layout components (`NavigationSuiteScaffold`). `minSdk` is 35 (Android 15), so don't add version checks for older APIs.
  - Theme: `MaterialExpressiveTheme` with `MotionScheme.expressive()`.
  - Screens are `Scaffold`s with a top app bar (`LargeFlexibleTopAppBar` on Today, `MediumFlexibleTopAppBar` on detail screens). Editors are full-screen `EditorScaffold` pages, not dialogs; keep `AlertDialog` for short confirmations and pickers only.
  - Shared building blocks live in `ui/components/` (grouped lists, `SwitchRow`, `DatePickerField`, `TimePickerField`, `UiFormat`). Format dates/times through `UiFormat` / `TimeOfDayBucket.formatClockTime` so the device 12/24-hour setting is honored.
  - Reversible actions (logging, deleting a log or schedule) post a Snackbar with **Undo** via `RegimenViewModel.messages` instead of asking for confirmation.
- **State Management**: Unidirectional Data Flow (UDF) through `RegimenViewModel` exposing `uiState: StateFlow<RegimenUiState>`.
- **Navigation & Back Handling**:
  - Predictive back gestures are enabled via `android:enableOnBackInvokedCallback="true"`.
  - Navigation uses **Navigation 3**: every destination is a serializable `Route` (`ui/navigation/Routes.kt`), and `Navigator` wraps the back stack. Don't add `BackHandler`s or `selectedXId` flags for screen flow; push a `Route` instead.
  - The back stack is `[Today]` or `[Today, <Tab>, …]`, so back from a secondary tab returns to Today before leaving the app.
  - Full-screen task routes (`isFullScreenTask`) hide the navigation bar.
- **Number display**: `formatNum` rounds for reading (three decimals, or three significant digits below 1). Use `formatExact` to pre-fill an editable field, so saving doesn't change a stored dose.
- **Prescription Label Parsing**:
  - On-device label extraction (`DrugBarcodeScanner.kt` and `DrugLabelParser.kt`) utilizes schema decoy fields (`patientName`, `pharmacyName`, `rxNumber`) and post-processing fuzzy token sanitization to ensure patient and prescriber names are never parsed as medication brand names.
  - Photos are transcribed with on-device text recognition (`LabelTextRecognizer`) and only the text goes to Gemini Nano. AICore's safety check refuses many label photos outright (`statusCode = 4`) but accepts the same label as text. Several photos of one bottle are joined so a label that wraps around it can be read.
- **Dose Windows (Buckets)**:
  - Windows can be added, deleted, and edited. They are **always ordered by start time** (`chronological()`); manual reordering was removed and `sortOrder` is legacy.
  - "Restore default windows" keeps any custom window a schedule still uses, so no medication loses its doses.
  - Windows can cross midnight (`crossesMidnight = true`). Receivers and alarm scheduling must account for overnight dates when querying scheduled items. After midnight, Today shows a still-open occurrence from yesterday as "From last night" (`DoseTimeline.stillOpenFromYesterday`).
  - Deleting a window goes through `WindowDeletion`: the user picks where its doses move, and logs move with them so a taken dose isn't due again. A move can drop a dose only if the target is already on the schedule, and the dialog says so.
  - A schedule whose windows no longer exist (`hasNoValidWindow`) produces no doses, so Today and the medication page flag it.
- **Schedules**:
  - Optional inclusive `startDateIso` / `endDateIso` (null end = ongoing), plus `startBucketId` / `endBucketId` for partial first/last days (e.g. a course started with the evening dose). Not applied to `SPECIFIC_DATES` / `AS_NEEDED`.
  - Outside the range a schedule produces no doses, but a dose that was actually logged always stays visible.
- **Notifications & Alarms** (`notifications/`):
  - `DoseNotificationManager.refresh(context, snapshot)` is the single entry point after any data, settings, clock, or permission change. It re-arms alarms (including running snoozes, which reboots clear) and reconciles the shade, and it is silent. Only receivers alert, once per event. Don't add separate schedule/sync calls. Creating `RegimenRepository` doesn't refresh: a receiver must refresh first with the window it's about to alert for, or the alert would be pre-empted by a silent post.
  - Per window, at most three alarm slots: START (next opening with doses due, looking ahead past empty days), END (only while the current window is open with unlogged doses), and NUDGE (repeat reminders on a fixed grid from window start). Snoozes get a per-dose alarm. Alarms carry the dose's date; never infer it from the clock.
  - **One notification per window occurrence**, tagged `window:<bucketId>:<dateIso>`: title "Morning dose" / "Morning medications", body lists drugs and doses, actions Take / Skip (single) or Take all (several), applying to exactly the listed doses. Discreet mode hides the body on the lock screen via a public version.
  - Pacing applies everywhere a dose can be logged. A notification or the alarm screen shows the limit (`PacingEvaluation.warningText`), and Take leaves out doses over it; logging one anyway happens in the app, which explains the limit first.
  - Swiping a reminder away = snooze (delete intent). Repeat reminders re-alert ones not acted on. There is no Snooze button on notifications (it exists on the alarm screen and in-app).
  - Receivers never call `startActivity`. The end-of-window alarm screen opens via the notification's full-screen intent (`DoseAlarmActivity.ACTION_RING` rings; `ACTION_SHOW` from a tap is silent).
- **Backup & Sync** (`sync/`, `data/BackupManager.kt`, `data/BackupKeyStore.kt`):
  - One format for backups and sync: `RegimenBackupBundle` (v1), JSON. There are no users yet, so there's no compatibility with older formats. `settings` is the exporting device's own settings (restored only by Replace); `sharedSettings` (patient name, tracking toggles) is what syncs. Reminder, alarm, theme and FDA-consent settings stay per device.
  - Bundles include soft-deleted tombstones (`getAll*(includeDeleted = true)`) so deletions propagate. Records carry `updatedAtEpochMillis`; dose windows and shared settings are stamped when edited.
  - `BundleMerger` is a three-way merge against the last agreed copy. Changed on one side → that change. Changed on both → the primary device keeps its own; other devices defer to the remote copy; with no primary, newer wins. Two logs for the same scheduled dose keep one and tombstone the other, ranked by status first (taken, skipped, snoozed); a snooze never overwrites a completed dose. File imports merge with no base and newer wins.
  - Any device can log; `PrimaryClaim` (highest epoch wins) only decides conflicts.
  - `BundleCrypto`: AES-256-GCM with a random data key wrapped by a PBKDF2 passphrase key in every file's header; the recovery code is the data key plus a checksum. The device caches the data key wrapped by an Android Keystore key. `regimen_device` and `regimen_backup_keys` prefs are excluded from Android Auto Backup.
- **Pacing & Doses**:
  - Daily limits count a dose on the calendar day it was taken (`DoseEngine.limitDayOf`), not its scheduled day and not a rolling 24 hours. Exceptions: taken during its own overnight window, or an unscheduled dose logged onto a past date, both stay on their scheduled day. Amount limits only total doses in the medication's default unit.
  - Logging from a medication's page completes the next dose due (`DoseTimeline.nextDoseFor`); an unscheduled dose is the separate "Log an extra dose". Today's "Log extra dose" button does the same for any medication (not only as-needed ones) on the day being viewed, through the same pacing and site flow; it's hidden on future days. Extra and as-needed doses can be logged now or at another date and time (`LogTimeDialog`); pacing for a back-dated dose is judged at that time (`evaluatePacing(backdated = true)`), and the dose counts on that time's own day.
  - A logged dose can be corrected from Today's log list or a medication's recent doses (`Route.DoseLogEditor`, `DoseLogEdits.edit`): time, amount, site and a note; a skipped dose only takes a note. Pacing isn't re-checked. A scheduled dose stays on its occurrence when its time changes; an unscheduled one follows the new time's day, but keeps its day if only the note changed. Saving posts an Undo that restores the previous version. Notes can also be added when logging an extra or as-needed dose (`LogTimeDialog`).
  - Show volumes through `Medication.doseVolumeMl`: prefilled devices have none, and "units" only convert to mL on a U-100 syringe.
  - FDA autofill never applies a name that isn't an exact match without asking (`exactNameMatch`): look-alike drugs can differ by a letter or two.
  - A complete NDC that resolves to an FDA record overrides the strength read from a label (`ndcMatchIsConclusive`), since OCR garbles strengths. The name must still match exactly.
- **Providers & Indications**:
  - "Provider" everywhere (model, tab, screens). A medication's provider is still labelled **Prescriber** (`Medication.prescriberId`), which is the role.
  - "Indication" everywhere (model, tab, screens): the reason a medication is taken, which isn't always a condition (PrEP is prevention).
  - Providers and indications are many-to-many, stored on the indication (`Indication.providerIds`) and editable from either side.
  - The provider list is split into Regular (`Provider.isRegular`: the PCP or `seenRegularly`), As needed, and Past. That can't be derived: nothing recorded tells a regular provider from an occasional one.
  - Medications and indications are many-to-many too, but stored on the medication (`Medication.indicationIds`), editable from the medication editor and from the indication's page (`setIndicationMedications`). Deleting an indication removes it from its medications; deleting a medication leaves its ids, since a deleted record is filtered out wherever it's read.
  - Indications are `ACUTE` or `PREVENTIVE` (status Active/Resolved, shown as Completed for preventive) or `CHRONIC`. An indication is archived ("past") when resolved or after its end date; an acute or preventive one without an end date ends with its linked medication courses if they all end (`IndicationTimeline`), so PEP archives itself and PrEP doesn't. Lists and pickers group by kind (`IndicationTimeline.groupedByKind`: acute, chronic, preventive), with past ones last. Pickers skip past indications and providers unless already linked. "Had this again" starts a new episode of a past acute or preventive indication.
  - The SQLite store stays compatible across this rename: `RegimenDatabase.onUpgrade` renames the table and moves older medication link keys to `indicationIds`. Backup files from before the rename lose their indications.
- **Medication List**:
  - `MedicationGroups` splits the list by the per-device `AppSettings.medicationGrouping` chip (schedule, indication, form), alphabetical within a section. Finished medications sit in one collapsed section under every grouping, and grouped by indication a medication appears under each indication it treats.
  - `MedicationGroups.courseOf` goes by the schedule, not the indication kind, since indications are optional and a chronic condition can need a short course. The one exception: an open-ended medication linked only to acute indications counts as short-term.
- **Optional Tracking**:
  - Providers and indications are optional (`AppSettings.trackProviders` / `trackIndications`; null = on only if records exist). Use `RegimenSnapshot.tracksProviders` / `tracksIndications` to gate tabs and fields. Hiding never deletes data.

