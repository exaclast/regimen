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

- **UI Framework**: Jetpack Compose with **Material 3 Expressive** (`material3` pinned to `1.5.0-beta02` above the BOM, which requires `compileSdk` 37 and AGP ≥ 9.1) and adaptive layout components (`NavigationSuiteScaffold`).
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
- **Prescription Label Parsing**:
  - On-device label extraction (`DrugBarcodeScanner.kt` and `DrugLabelParser.kt`) utilizes schema decoy fields (`patientName`, `pharmacyName`, `rxNumber`) and post-processing fuzzy token sanitization to ensure patient and prescriber names are never parsed as medication brand names.
- **Dose Windows (Buckets)**:
  - Windows can be added, deleted, and edited. They are **always ordered by start time** (`chronological()`); manual reordering was removed and `sortOrder` is legacy.
  - Windows can cross midnight (`crossesMidnight = true`). Receivers and alarm scheduling must account for overnight dates when querying scheduled items.
- **Schedules**:
  - Optional inclusive `startDateIso` / `endDateIso` (null end = ongoing), plus `startBucketId` / `endBucketId` for partial first/last days (e.g. a course started with the evening dose). Not applied to `SPECIFIC_DATES` / `AS_NEEDED`.
  - Outside the range a schedule produces no doses, but a dose that was actually logged always stays visible.
- **Notifications & Alarms** (`notifications/`):
  - `DoseNotificationManager.refresh(context, snapshot)` is the single entry point after any data, settings, clock, or permission change. It re-arms alarms and reconciles the shade, and it is silent. Only receivers alert, once per event. Don't add separate schedule/sync calls.
  - Per window, at most three alarm slots: START (next opening with doses due, looking ahead past empty days), END (only while the current window is open with unlogged doses), and NUDGE (repeat reminders on a fixed grid from window start). Snoozes get a per-dose alarm. Alarms carry the dose's date; never infer it from the clock.
  - **One notification per window occurrence**, tagged `window:<bucketId>:<dateIso>`: title "Morning dose" / "Morning medications", body lists drugs and doses, actions Take / Skip (single) or Take all (several), applying to exactly the listed doses. Discreet mode hides the body on the lock screen via a public version.
  - Swiping a reminder away = snooze (delete intent). Repeat reminders re-alert ones not acted on. There is no Snooze button on notifications (it exists on the alarm screen and in-app).
  - Receivers never call `startActivity`. The end-of-window alarm screen opens via the notification's full-screen intent (`DoseAlarmActivity.ACTION_RING` rings; `ACTION_SHOW` from a tap is silent).
- **Backup & Sync** (`sync/`, `data/BackupManager.kt`, `data/BackupKeyStore.kt`):
  - One format for backups and sync: `RegimenBackupBundle` (v1), JSON. There are no users yet, so there's no compatibility with older formats. `settings` is the exporting device's own settings (restored only by Replace); `sharedSettings` (patient name, tracking toggles) is what syncs. Reminder, alarm, theme and FDA-consent settings stay per device.
  - Bundles include soft-deleted tombstones (`getAll*(includeDeleted = true)`) so deletions propagate. Records carry `updatedAtEpochMillis`; dose windows and shared settings are stamped when edited.
  - `BundleMerger` is a three-way merge against the last agreed copy. Changed on one side → that change. Changed on both → the primary device keeps its own; other devices defer to the remote copy; with no primary, newer wins. Two logs for the same scheduled dose keep one and tombstone the other. File imports merge with no base and newer wins.
  - Any device can log; `PrimaryClaim` (highest epoch wins) only decides conflicts.
  - `BundleCrypto`: AES-256-GCM with a random data key wrapped by a PBKDF2 passphrase key in every file's header; the recovery code is the data key plus a checksum. The device caches the data key wrapped by an Android Keystore key. `regimen_device` and `regimen_backup_keys` prefs are excluded from Android Auto Backup.
- **Optional Tracking**:
  - Prescribers and conditions are optional (`AppSettings.trackPrescribers` / `trackConditions`; null = on only if records exist). Use `RegimenSnapshot.tracksPrescribers` / `tracksConditions` to gate tabs and fields. Hiding never deletes data.

