# Regimen: Done and Retired

What has shipped, and what was merged or dropped. Open work is in `BACKLOG.md`; reasons are in `DECISIONS.md`.

**Adding an entry.** When a task is finished, delete its row from `BACKLOG.md` and add one row at the top of **Shipped**:
the T-number (or old ID), what changed, the commit, and the proof (tests, device, or "not run"). Anything left over goes
back into the backlog as a new task. Old audit and plan IDs (A, B, O, R, U) stay listed so searches in `archive/` still
resolve.

---

## Shipped

Newest first.

- **Pre-beta bug sweep, sample data update, and Pixel verification** (uncommitted v1.0 / M5):
  - **T-007, T-008**: Moved `AlarmRingtonePlayer` `MediaPlayer` preparation off the main thread onto a background coroutine with failure cleanup; removed obsolete `"Snooze, "` from `channel_window_description`.
  - **T-031, T-032, T-033, T-034, T-035**: Tightened `DrugLabelParser` fuzzy patient/prescriber token filtering (`length >= 5` prefix / `length >= 4` edit distance `<= 1`), fixed `FdaNdcLookupService.parseGs1DateToIso` month-end `00` resolution via `YearMonth` and strict calendar validation, preserved ambiguous 10-digit label NDCs (`normalizeIfUnambiguousNdc`), disabled both lookup actions during `isLookingUpNdc`, and collapsed identical multi-salt strengths (`displayStrength`).
  - **T-041, T-042, T-043, T-046, T-048**: Added `careEndDateIso < careStartDateIso` validation in `ProviderEditorScreen`; rejected non-finite numbers in `NumberInput.parseDecimal`; preserved `createdAtEpochMillis` across `Adjustments.applyMedicationEdit`, `RegimenRepository.logDoses` (sequential multi-dose timestamps), and `MedicationEditorScreen`; formatted single specific-date schedules with localized medium dates (`ScheduleText`); stacked paired form fields cleanly with `AdaptiveFieldPair` and simplified `"Every N Days"` (`enum_freq_every_n_days`).
  - **T-075, partial T-077**: Increased `TodayScreen` bottom list padding (`112.dp`) when showing the `Log extra dose` FAB so bottom card actions aren't covered; added leading icons to all `DoseCard` overflow menu actions so labels align (collapsed app bar rail strip remains in `T-077`).
  - **Additional bug fixes**: Added Greek mu (`μg`) parsing in `Concentration.kt`; separated daily dose-count vs daily-amount pacing limits (`isDailyDoseLimitReached` / `isDailyAmountLimitReached` in `DoseEngine.kt` & `TodayScreen.kt`); stopped snoozes from locking `suggestedSite` (`DoseEngine.kt`, `DoseActionReceiver.kt`); cancelled obsolete snooze alarms on delete/restore/reset and clamped `saveIndication` resolved date (`RegimenRepository.kt`); localized `"Undo"`, `UiFormat.dateTime` (`"Today"` / `"Yesterday"`), and `"end of medication course"`, and added `navigator.backFrom` guards for deleted records (`RegimenApp.kt`); applied `Adjustments.asOf` in `CareSummary`, `MedicationListScreen`, `PrnDoseItem.schedule`, and `TodayScreen.extraDoseItem`; fixed `IndicationEditor` `Severity` slider sync on Clear and cleared/ignored `endDateIso` for allergies/intolerances; fixed `MedicationEditor` `sitesChosen`, non-Other `syringeVolumeMl` check, and `ScheduleEditorScreen` `daysOfWeek` set comparison; preserved `LogTimeDialog` & `ExtraDosePickerDialog` state across rotation (`rememberSaveable` in `TodayScreen.kt` and `MedicationDetailScreen.kt`).
  - **Sample data (`seedInitialSampleData`)**: Updated demo data across 4 providers (`Regular` PCP & specialist, `As needed`, `Past`), 11 indications (including `Suspected`, `Secondary to`, Drug/Food/Environmental allergies, Drug `Intolerance`, Preventive, and `Past`), 7 medications (`Augmentin` partial course + pacing, `Acetaminophen` PRN, `Lantus` U-100 + `ROTATE`, `Vitamin B12` IM Luer-lock + ISO 6009 hub colors + `DAYS_OF_WEEK` + recent log, `Zaditor` `RECORD` eye drops, `Isotretinoin` active/future `defaultDose` + schedule adjustments, `Prednisone` finished course + 90-day log).
  Proof: `./gradlew testDebugUnitTest lintDebug assembleDebug` (`295` tests across `32` suites, `0` failures, `0` lint errors); full feature walkthrough on Pixel 10 Pro (`57181FDCH0072D`), including confirmed **Device checks** for `As-needed unit`, `Extra-dose search`, and `Log-time dialog, short window` (`T-011`).

- **T-056** (Play policy compliance and Data Safety documentation; uncommitted v1.0 / M5):
  - Removed `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from `AndroidManifest.xml`, `DoseNotificationManager.kt`, `StartupPermissionsDialog.kt`, and `strings.xml` (eliminating the Lint `BatteryLife` Play policy violation; `setAlarmClock`, `setExactAndAllowWhileIdle`, and `WorkManager` handle wake-ups and backups without battery exemptions).
  - Documented Play Console restricted permission justifications (`USE_FULL_SCREEN_INTENT` with graceful heads-up fallback and `SCHEDULE_EXACT_ALARM`), Data Safety answers (optional OpenFDA lookup, local AES-GCM backups, Auto Backup exclusions, and ML Kit SDK diagnostics note), and submission checklist in `docs/play-store-compliance.md`.
  Proof: `lintDebug` (0 errors, `BatteryLife` resolved), `testDebugUnitTest` (287 passed), verified 3-item permissions checklist on Pixel (`57181FDCH0072D`).

- **T-087** (R8 code & resource shrinking and ProGuard rules; "Complete M5 (v1.0): auto backups, adaptive layout, and release gates"):
  - Enabled `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts` `release` build type.
  - Added `app/proguard-rules.pro` keeping `com.exaclast.regimen.ui.navigation.Route**` for Navigation 3 `NavKey` state restoration (while relying on bundled consumer rules for Kotlin Serialization, Room, WorkManager, and ML Kit).
  Proof: `app:assembleRelease` succeeded cleanly.

- **T-090** (dependency updates and unused dependency cleanup; "Complete M5 (v1.0): auto backups, adaptive layout, and release gates"):
  - Updated `kotlinx-serialization-json` (`1.13.0`), `androidxCore` (`1.19.1`), `androidxLifecycle` (`2.11.0`), `androidxComposeBom` (`2026.09.00`), `androidxWindow` (`1.5.1`), `coroutines` (`1.11.0`), `nav3Core` (`1.2.1`), `material3` (`1.5.0-rc01`, latest pre-1.5-stable for Expressive APIs), `mlkitGenaiPrompt` (`1.0.0-beta5`), and Kotlin (`2.4.21`) in `gradle/libs.versions.toml`.
  - Removed unused `androidx-lifecycle-viewmodel-navigation3` and `androidx-compose-ui-tooling-preview` while keeping `androidx.window` and adaptive libraries for M2.
  Proof: `studio_gradle_sync`, `testDebugUnitTest` (287 passed), `assembleDebug`, `assembleRelease`, live smoke test on Pixel (`57181FDCH0072D`).

- **T-093** (automatic encrypted local backups; "Complete M5 (v1.0): auto backups, adaptive layout, and release gates"):
  - Added `androidx.documentfile` and `androidx.work:work-runtime-ktx`, and excluded `regimen_auto_backup.xml` from `backup_rules.xml` and `data_extraction_rules.xml`.
  - Implemented `AutoBackupRetention.kt` (collision-safe naming, retention keeping last `N` backups plus newest per calendar month for 12 months, ignoring non-Regimen files), `AutoBackupPreferences.kt`, `AutoBackupManager.kt`, `AutoBackupWorker.kt`, and byte/stream export helpers on `BackupManager`.
  - Wired debounced automatic backup scheduling from `RegimenRepository.reloadSnapshot`, view-model actions (`RegimenViewModel.kt`, `RegimenApp.kt`), and folder/frequency/retention/"Back up now" controls in `SettingsBackupScreen.kt`.
  Proof: `testDebugUnitTest` (287 passed, including `AutoBackupRetentionTest` and `AutoBackupManagerTest`), verified Backup screen UI on Pixel (`57181FDCH0072D`).

- **T-066, T-072, T-078, T-079** (adaptive width cap, compact top bar, 200% font scale & TalkBack accessibility; "Complete M5 (v1.0): auto backups, adaptive layout, and release gates"):
  - **T-066**: Added `ContentMaxWidth = 720.dp` and `CenteredContentBox` / `addHorizontal` in `Components.kt`, centering single-column content on wide windows across Today, list/detail tabs, `CareSummaryScreen`, `BackupScreens`, `DoseAlarmActivity`, and all Settings screens while keeping scroll containers full-width.
  - **T-072**: Added `isCompactWindowHeight()`, `AdaptiveLargeTopAppBar`, and `AdaptiveMediumTopAppBar` to switch from `LargeFlexibleTopAppBar` / `MediumFlexibleTopAppBar` to compact `TopAppBar` on short windows.
  - **T-078, T-079**: Added `isLargeFontScale()`, `AdaptiveSingleChoiceRow`, and `AdaptiveFieldPair` so segmented bars and paired fields stack/wrap at $\ge 1.35\times$ font scale; added "Take at a different site" `CustomAccessibilityAction` (`T-079`) on dose cards and `Role.Checkbox` + `FormatStyle.FULL` labels on specific-dates calendar cells (partial `T-080`).
  Proof: `testDebugUnitTest` (287 passed), `lintDebug` (0 errors).

- **T-029, T-039, T-040, T-044** (v1.0 release-gate bug fixes; "Complete M5 (v1.0): auto backups, adaptive layout, and release gates"):
  - **T-029**: Separated `FdaNetworkException` / network error handling from empty OpenFDA search results in `FdaNdcLookupService.kt` (`CrossReferenceOutcome.Error`) with unit tests in `FdaNdcLookupServiceTest.kt`.
  - **T-039**: Added `hasUnsavedChanges` tracking and discard confirmation (`BackHandler` + close prompt) to `EditorScaffold` (`Components.kt`) and editors.
  - **T-040**: Cleared `endDateIso` when toggling a resolved indication back to Active in `IndicationsScreen.kt` so it returns to the Active list.
  - **T-044**: Added `presetWindowTimes` in `DoseWindowEditorScreen.kt` so picking a window name preset chip updates default start/end times unless the user already edited times.
  Proof: `testDebugUnitTest` (287 passed, including updated `FdaNdcLookupServiceTest`).

- **T-085** (Robolectric test suite for repository and receivers; "Complete M3 (v0.9): foundations, schedule versioning, and tests"):
  - Added Robolectric `4.18` and `androidx.test:core` with `isIncludeAndroidResources = true` in `app/build.gradle.kts` and `gradle/libs.versions.toml`, plus `@VisibleForTesting internal fun resetForTests()` on `RegimenRepository` and `DoseNotificationManager`.
  - Added `RegimenRepositoryTest` (7 Robolectric tests covering backup bundle validation, SQLite trigger mid-transaction rollback preserving prior DB rows and prefs, soft-deleted `time_buckets` tombstone & restore, `PrimaryClaim`/`SyncDevice` persistence & merge convergence, tombstone preservation & random UUIDs across demo/clear, batch `logDoses` + `restoreAndDeleteDoseLogs`, and atomic `saveScheduleEdit`).
  - Added `DoseActionReceiverTest` (7 Robolectric tests covering `ACTION_TAKE` single and multi-item with pacing block, `ACTION_SNOOZE`, quiet `ACTION_DISMISSED`, `ACTION_SKIP`, `ACTION_TRIGGER_WINDOW_START`, `ACTION_TRIGGER_SNOOZE_EXPIRED`, and `ACTION_BOOT_COMPLETED`).
  Proof: `testDebugUnitTest` (278 tests pass, including 14 Robolectric tests).

- **T-014, T-015** (schedule versioning and same-day window guard; "Complete M3 (v0.9): foundations, schedule versioning, and tests"):
  - Added `ScheduleVersioning` (`domain/ScheduleVersioning.kt`) for `DOSE`, `FREQUENCY`, `WINDOWS`, and `DATES` changes: prompts "Change from a date" vs "Fix" when a schedule has logs, adjustments, or past scheduled days (skipping future-only specific dates and date-range bound edits); splits edited schedules by ending the prior version at `effectiveDate - 1` and inserting a new version from `effectiveDate` (preserving interval `anchorDateIso`, soft-deleting old `AS_NEEDED` schedules when switching to timed, and migrating future `MedicationAdjustment.scheduleDoses` entries).
  - Updated `DoseEngine.buildDailyBoard` to guard same-day added windows in Fix mode via `addedWindowIdsToday` / `addedWindowDateIso`, restrict `createdAfterWindow` to schedules created on `targetDate`, and match same-day logs from ended prior schedule versions against replacement schedules without duplicate items.
  - Added atomic `RegimenRepository.saveScheduleEdit`, wired `ChangeOrFixDialog(showNote = false)` in `Route.ScheduleEditor`, filtered ended schedules in `Route.AdjustmentEditor`, and collapsed ended schedules under "Past schedules (N)" in `MedicationDetailScreen`.
  Proof: `testDebugUnitTest` (278 tests pass, including new `ScheduleVersioningTest` and `DoseEngineTest` cases); verified on Pixel (`57181FDCH0072D`) editing Augmentin's windows with `ChangeOrFixDialog`, splitting into "Past schedules (1)", and preserving yesterday's 2-window board.

- **T-060** (offload receivers and worst reload-on-tap path; "Complete M3 (v0.9): foundations, schedule versioning, and tests"):
  - Updated `DoseActionReceiver` to use `goAsync()` and run work off the main thread on a serialized `backgroundDispatcher`, replacing per-item DB loops with batched `RegimenRepository.logDoses` and `restoreAndDeleteDoseLogs` synchronized writes.
  - Moved `DoseAlarmActivity` dose logging onto `Dispatchers.IO` via `logDoses`.
  - Optimized `DoseEngine.buildDailyBoard` by pre-indexing logs, buckets, and per-medication day context; moved `RegimenViewModel` daily board calculation onto `Dispatchers.Default` (`flowOn`) and dose-logging / initial refresh writes onto `Dispatchers.IO`.
  Proof: `testDebugUnitTest` (278 tests pass, including `DoseActionReceiverTest` and `RegimenRepositoryTest`); verified smooth board updates and navigation on Pixel (`57181FDCH0072D`).

- **T-061, T-062, T-063, T-064** (sync readiness, tombstones, deterministic merge, and identity preservation; "Complete M3 (v0.9): foundations, schedule versioning, and tests"):
  - **T-061**: Added `deletedAtEpochMillis` soft-deletion to `TimeOfDayBucket`, bumped `RegimenDatabase` to v6 (`deleted_at` column on `time_buckets` with migration), and preserved bucket tombstones across repository reads/writes and sync bundles.
  - **T-062**: Persisted `PrimaryClaim` and `SyncDevice` list in `regimen_prefs` across `currentBundle` and `applyBundle` so sync state survives reloads.
  - **T-063**: Fixed `SharedSettings.serializer()` usage and added deterministic SHA-256 content tie-breaking in `BundleMerger` so equal-timestamp conflicts converge identically across devices.
  - **T-064**: Updated `clearAllUserData` and `resetToSampleData` to soft-delete records (preserving tombstones for sync) and generate random UUIDs for demo records instead of fixed IDs.
  Proof: `testDebugUnitTest` (278 tests pass, including `BundleMergerTest` and `RegimenRepositoryTest`); DB v6 migration and `Load demo data` verified on Pixel (`57181FDCH0072D`).

- **T-054, T-055, T-059** (atomic restore, hardened backup reading, and recovery code flow; "Complete M3 (v0.9): foundations, schedule versioning, and tests"):
  - **T-054**: Added unsaved recovery code confirmation on ✕ and Back in `BackupScreens` and deferred adoption of a recovery-unlocked backup's passphrase until restore confirmation in `BackupManager` / `RegimenViewModel`.
  - **T-055**: Hardened `BundleCrypto` and `BackupKeyStore` by capping header PBKDF2 iterations, capping backup file and decompressed sizes, clearing key material after use, caching `isConfigured`, and surfacing lost Android Keystore key diagnostics in `SettingsBackupScreen`.
  - **T-059**: Reordered `RegimenRepository.applyBundle` so shared settings preferences are committed only after the SQLite database transaction succeeds.
  Proof: `testDebugUnitTest` (278 tests pass, including `BundleCryptoTest` and `RegimenRepositoryTest`).

- **T-122** (Settings page categorization and modular screen split; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Reworked the monolithic 1,360-line Settings screen into a categorized hub with 6 distinct destination routes:
    - `Route.SettingsReminders` (`SettingsRemindersScreen.kt`): Dose reminders, end-of-window alarm, snooze interval, repeat interval, confirm before logging, dose windows management, and notification & alarm permissions checklist.
    - `Route.SettingsMedications` (`SettingsMedicationsScreen.kt`): Providers and indications tracking toggles, discreet notifications, and printed label name.
    - `Route.SettingsBackup` (`SettingsBackupScreen.kt`): Encrypted export, restore, plain JSON export, passphrase management, and danger zone actions (sample data, delete all data).
    - `Route.SettingsAppearance` (`SettingsAppearanceScreen.kt`): Theme selection (System, Light, Dark) and dynamic wallpaper colors.
    - `Route.SettingsScanning` (`SettingsScanningScreen.kt`): On-device Gemini Nano model status/downloading and OpenFDA medication lookup toggle.
    - `Route.SettingsDiagnostics` (`SettingsDiagnosticsScreen.kt`): Test reminders and test alarms (debug builds only).
  - Extracted `DoseWindowEditorScreen.kt` (window editor and delete dialog) and `StartupPermissionsDialog.kt` (permissions setup & checklist).
  - All category screens support deep-linking, standard Navigation 3 navigation with predictive back, and hide the bottom navigation bar (`isFullScreenTask`).
  Proof: `testDebugUnitTest` (247 tests pass), `lintDebug` passes clean, `assembleDebug` passes, live interactive verification on Android emulator across all 6 categories and back navigation.

- **T-019** (logs for deleted medications display medication name and allow editing; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - In `RegimenRepository.kt`, updated `readSnapshot` to load tombstoned medications via `db.getAllMedications(includeDeleted = true)` into `RegimenSnapshot.deletedMedications`, providing `snapshot.allMedications` and `snapshot.medication(id)`.
  - In `TodayScreen.kt`, passed `snapshot.allMedications` to `DayLogList` so logs for deleted medications resolve their primary name and enable tapping to open the log editor instead of falling back to "Medication" and staying read-only.
  - In `RegimenApp.kt`, routed `Route.DoseLogEditor` through `snapshot.medication(l.medicationId)` to resolve tombstoned medications when opening the dose log editor.
  - In `CareSummary.kt`, included recently taken deleted medications in the finished/as-needed medications summary section.
  Proof: `testDebugUnitTest` (247 tests pass, including new `medicationLookup_findsActiveAndDeleted_andAllMedicationsCombinesThem` in `OptionalTrackingTest` and `deletedMedication_withRecentLogs_isIncludedInPastSevenDaysSection` in `CareSummaryTest`), `lintDebug` passes clean.

- **T-012, T-013, T-016** (early take confirmation friction, past day action buttons and timestamps, schedule start dates; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - **T-012**: In `TodayScreen.kt`, added an explicit confirmation dialog (`earlyPrompt`) when "Take early" is tapped $\ge 1$ hour ahead of a window's start time, warning the user and asking for confirmation. On past days (`isPastDay`), swapped button weights so "Taken on time" is the primary filled `Button` and "Take now" is an `OutlinedButton`.
  - **T-013**: In `ScheduleEditorScreen` (`MedicationEditorScreen.kt`), if a new schedule is created with dose windows that have all already ended today, the start date defaults to tomorrow (`today.plusDays(1)`); if some have ended, the start window defaults to the next upcoming window today. In `DoseEngine.buildDailyBoard`, doses are not generated for windows that ended before the schedule was created (`createdAtEpochMillis`) on its start date unless already logged.
  - **T-016**: In `LogTimeDialog.kt`, default `otherTime` to true when viewing or logging for past days (`isPastDay`). In `RegimenViewModel.logPrnOrCustomDose`, logging "Now" (`takenAtMillis == null`) records the dose on today's date rather than stamping current time onto a past date. On `TodayScreen.kt`, logging PRN from a past day opens `LogTimeDialog` with `startWithOtherTime = true`.
  Proof: `testDebugUnitTest` (245 tests pass, including new `newSchedule_createdAfterWindowEnded_doesNotProduceDoseOnStartDate` in `DoseEngineTest`), `lintDebug` passes clean, verified on Android emulator.

- **T-005, T-006** (alarm screen silencing on notification open, backgrounding, and home press; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - In `DoseAlarmActivity.handleLaunch`, invoke `silenceAlarmNotifications` on any launch (whether `ACTION_RING` or `ACTION_SHOW`) so tapping the notification from the shade immediately silences the notification's insistent copy and lets the screen own any audio.
  - Implemented `onStop` in `DoseAlarmActivity` to silence `AlarmRingtonePlayer` when the activity is stopped (e.g. user presses Home button, switches apps, or screen turns off, while preserving ringing across configuration changes like screen rotation).
  Proof: `testDebugUnitTest` (244 tests pass), `studio_analyze_file` clean, verified on Android emulator.

- **T-052** (privacy text accuracy and Auto Backup database exclusion; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Updated patient name descriptions in Settings (`settings_label_name_help`) and Setup (`setup_name_help`) to accurately reflect that the name is stored in app settings and encrypted backups rather than "stays entirely on-device".
  - Clarified label scanning privacy (`setup_nano_help`) to accurately state that photos and label text are not sent to the cloud, while noting that Google ML Kit may send basic SDK diagnostic metrics.
  - Excluded the unencrypted database (`regimen_store.db`, `regimen_store.db-wal`, `regimen_store.db-shm`) from Android Auto Backup in `backup_rules.xml` and from cloud backup in `data_extraction_rules.xml`, per decision D-3, protecting medical data with the app's passphrase-based encryption rather than cloud device backups.
  - Moved D-3 to Made in `DECISIONS.md`.
  Proof: `testDebugUnitTest` (244 tests pass), `lintDebug` passes clean.

- **T-053** (passphrases in saved state and recovery code `FLAG_SECURE`; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - In `BackupScreens.kt`, replaced `rememberSaveable` with `remember` for passphrase input fields (`current`, `newPass`, `confirm`) and passphrase visibility toggle to prevent user passphrases from persisting into Android saved instance state bundles.
  - Added `SecureScreenEffect` which applies `WindowManager.LayoutParams.FLAG_SECURE` to the Activity window while displaying the recovery code, and clears it on disposal. This prevents screenshots, screen recordings, and hides the recovery code from the Android Recents task switcher preview.
  Proof: `testDebugUnitTest` (244 tests pass), `studio_analyze_file` clean.

- **T-051** (alarm screen discreet mode over lock screen; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Tracked keyguard locked state (`KeyguardManager.isKeyguardLocked`) in `DoseAlarmActivity` across creation, resume, and window focus changes.
  - When discreet mode (`settings.discreetNotifications`) is active and the device is locked, `DoseAlarmScreen` renders a privacy-preserving lock card showing only the window and dose count summary (e.g., "Morning · 2 doses due") and an "Unlock to view and log" button (`requestDismissKeyguard`).
  - Allows Snooze and Dismiss directly from the lock screen without unlocking, while strictly hiding all medication names, dosages, strengths, injection sites, needle specs, and warnings until authentication succeeds.
  Proof: `testDebugUnitTest` (244 tests pass), `studio_analyze_file` clean, verified on Android emulator.

- **T-027** (OpenFDA records with no package getting made-up NDCs; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Made `FdaDrugLookupResult.ndc11` nullable (`String?`), returning `null` when an OpenFDA drug record has no package NDC instead of fabricating a synthetic `<product>-01` or `00000-0000-00` code.
  - In `MedicationEditorScreen`, autofill leaves the NDC field blank (`""`) when OpenFDA returns a record without an NDC, and candidate lists display "None" (`value_none`) rather than a fabricated NDC.
  Proof: `testDebugUnitTest` (244 tests pass, including `parseOpenFdaJson_recordWithNoPackageHasNullNdc` in `FdaNdcLookupServiceTest`).

- **T-084** (CI lint gate and workflow security hardening; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Added `lintDebug` to both debug and release CI jobs in `.github/workflows/android-release.yml`.
  - Scoped top-level workflow permissions down to `contents: read`; granted `contents: write` exclusively to the `build-and-release` job.
  - Configured release job to fail explicitly if `SIGNING_KEYSTORE_BASE64` is missing rather than publishing unsigned artifacts, and removed unsigned APK packaging.
  - Passed `inputs.version_name` via environment variable (`INPUT_VERSION_NAME`) rather than inline shell expansion.
  - Pinned all third-party GitHub Actions (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, `actions/upload-artifact`, `softprops/action-gh-release`) to full immutable commit SHAs.
  - Disabled legacy V1 signing (`enableV1Signing = false`) in `app/build.gradle.kts` (minSdk 35 uses V2/V3 signing).
  Proof: `testDebugUnitTest lintDebug assembleDebug` and `assembleRelease bundleRelease` build and verify cleanly.

- **T-068** (text button contrast on tinted cards in dark theme; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Styled `TextButton` actions inside tinted containers (`NoticeCard`, `WindowCard` "Take all", and `MedicationDetailScreen` hero "Other time..." / "Extra dose") with their surface's corresponding `onContainer` color (`onTertiaryContainer`, `onContainer`, `onPrimaryContainer`) via `ButtonDefaults.textButtonColors(contentColor = ...)`.
  - Eliminates unreadable light-on-light contrast where `TextButton`'s default `primary` color washed out against tinted card containers in dark theme.
  Proof: `testDebugUnitTest` (243 tests pass), verified live on Android emulator with dark theme.

- **T-026** (scan prompt overwriting user dose; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - Replaced literal `"defaultDoseAmount": 1.0` in `DrugLabelParser.buildLabelExtractionPrompt` with a descriptive placeholder instructing Gemini Nano to extract the numeric dose per administration only when explicitly stated in instructions, and to return null (never defaulting to 1) when unspecified.
  - Implemented `FlexibleDoubleSerializer` to deserialize numbers, string numbers ("2"), fractions ("1/2"), and nulls into `Double?`, ensuring `ParsedMedicationLabel.defaultDoseAmount` is null when unspecified so scanning never overwrites an existing or user-entered dose with 1.
  Proof: `testDebugUnitTest` (243 tests pass, including prompt check and dose variation suite in `DrugLabelParserTest`).

- **T-003, T-004** (test notifications and demo data safe isolation; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - **T-003**: Test reminder and end-of-window alarm actions in Settings Diagnostics (`postTestNotification`) post labelled notifications with tag `"test_notification"` and id `99999` using `ACTION_DISMISS_TEST`. Tapping the notification or swiping it away only cancels the test and never logs or snoozes real doses or arms alarms. Diagnostics testing is enabled even when no active doses are scheduled.
  - **T-004**: Loading demo data (`resetToSampleData`) cancels active notifications and explicitly turns off `notificationsEnabled` and `alarmsEnabled` in `AppSettings` to prevent sample data from firing real alarms or alerting the user. The "Load demo data" button and confirmation dialog are hidden on release builds (`isDebugBuild`). Dialog confirmation clarifies that sample data replaces existing records and disables reminders/alarms.
  Proof: `testDebugUnitTest` (241 tests pass), verified live on Android emulator (test reminder and alarm posted to notification shade with "Dismiss" action, verified in `dumpsys notification`, dismissed cleanly without logging or snoozing doses; demo data loaded with reminders/alarms disarmed and disarmed banner displayed).

- **T-057, T-058** (the two P0 data integrity bugs; "Complete M1 (v0.8): harden trust, data integrity, and Settings"):
  - **T-057**: Backups validated before preview (`BackupFiles.validate`, `InvalidBackupException`) checking required fields, value bounds (bucket hours 0..23, minutes 0..59, non-zero duration), ISO dates, and relational integrity (schedules/logs referencing existing medications and windows, provider and indication links, unique IDs, no self-secondary indications). Plain JSON requires the `"format": "regimen_backup"` marker; `{}` and arbitrary JSON files are rejected. `TimeOfDayBucket` enforces field bounds and non-zero duration in `init`.
  - **T-058**: Undecodable rows in SQLite are logged with table and row ID and kept untouched. `RegimenDatabase.undecodableRowCount()` scans all tables; `BackupManager.exportEncrypted` and `exportPlainJson` check database integrity and stop exports with a localized user message (`backup_err_unreadable_records`) if any unreadable rows exist, preventing silent data loss.
  Proof: `testDebugUnitTest` (241 tests pass, including `BackupFilesTest` format and validation suites, and `TimeOfDayBucketTest`), `assembleDebug` passes.

- **T-067** (bottom inset applied twice): the four tab screens' Scaffolds no longer pad for the system navigation bar when the bottom bar is showing (`tabContentInsets`, `LocalBottomBarShown`), so the empty band over the bar is gone and the floating button sits one margin above it. Rails and full-screen tasks keep the inset. Proof: seen on the emulator (Today, Medications). **T-081** and **T-098** are closed too: the UI text is in `strings.xml` (see localization, below), and the health summary's share button covers sharing the list as text (a PDF is T-099).

- **T-118, T-119 (localization, phases 2 and 3)**: nothing the user reads is English-only in code any more. Enums implement `Labeled` (`labelRes`; `label()` in Compose, `Resources.label` elsewhere). Text built outside Compose takes a `Texts` (a `Resources` wrapper in the app, `TestTexts` in tests, which reads `strings.xml`): `CareSummary`, `Adjustments.describe`, `ScheduleText`, `volumeHint`, `PacingEvaluation.warningText`/`nextAllowedText`, `MedicationSection.label`, the notification text. Messages decided before any UI exists (FDA lookup results and errors, label-scan progress, backup errors, field validation) are a `Message` (resource id and arguments) or a `MessageException`, resolved where they're shown. The view model and the notification receiver use the application's resources. The change dialog's sentence is a locale-aware list of part names (`ListFormatter`). Removed the unused `PacingEvaluation.statusSummary`. Also in this change: a severity slider with in-between levels, no severity, suspected or secondary options for preventive indications, and the alarm card's strength under the drug name. Proof: `testDebugUnitTest` and `assembleDebug` pass; the summary, medication list and providers seen on the emulator; `lintDebug` has only the pre-existing `MissingPermission` error (T-120).

- **Secondary indications** (see this session's commit): `Indication.primaryIds` links an indication to the ones it is secondary to. Editor chips under "Secondary to", a detail row and the reverse "Secondary conditions", "Secondary to X" in list lines and in the health summary. Proof: unit test; device check added.

- **Localization, phase 1**: every user-visible string in the Compose UI (670 resources across the screens, dialogs, alarm screen and navigation) now lives in `res/values/strings.xml`, with `<plurals>` for counts. The locale list is generated from the resource folders (`androidResources.generateLocaleConfig`, default language in `res/resources.properties`), so a translation is a new `values-xx/strings.xml` and it appears in the system's per-app language picker. Weekday names follow the device locale. Phases 2 and 3 are below; T-120 is what's left. Proof: `testDebugUnitTest` and `assembleDebug` pass; the summary, Today and navigation seen on the emulator; `lintDebug` has one error, pre-existing.

- **Health summary**: a screen (Today's top bar, document icon) showing drug allergies and intolerances first, then other allergies, the current regimen, finished or as-needed medications taken in the past 90 days (last dose and when), indications, suspected conditions and providers. The share button sends the same content as plain text (`CareSummary.build` / `toText`). Proof: 5 unit tests pass, builds; not yet seen on a device.

- **T-117** (uncommitted): allergies and intolerances as two separate kinds, `IndicationKind.ALLERGY` and `INTOLERANCE`, so one is never labelled as the other: the name is the trigger, plus `allergenType` (drug, food, environmental), `severity` and `reaction`. Listed together under "Drug / Food / Environmental allergies and intolerances" (`IndicationTimeline.sections`, also used by the pickers), each labelled with its own kind; the Type selector is now wrapping chips, can be Suspected, resolved, linked to providers and to the medications that treat them. Recording only; no match warning. Stored in the JSON payload with defaults, so no migration. Proof: unit tests pass (grouping, status, no course end); device check added.

- **T-116** (uncommitted): `Indication.suspected` for conditions and symptoms not formally diagnosed. Editor switch, own "Suspected" section on the Indications tab, noted in summaries and the detail page; older records read as diagnosed (no migration needed, it's a JSON default). Proof: unit tests pass (default, new episode); device check added.

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
