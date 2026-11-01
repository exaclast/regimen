# Regimen (`com.exaclast.regimen`)

**Regimen** is a low-friction, adaptive native Android medication and dose-tracking application built with Jetpack Compose and Material 3 Adaptive layouts. It is designed from the ground up for ADHD-friendly adherence, complex injectable/topical protocols with automated anatomical site rotation, and seamless support across phones, foldables, tablets, and x86/ARM desktop environments (Chromebooks / Android Desktop).

---

## Key Features

### 1. Comprehensive Medication Tracking
- **Core Details**: Formula/generic name, strength, dosage form (`Tablet`, `Capsule`, `Injectable (Vial)`, `Injectable (Pen)`, `Topical`, `Oral Liquid`, `Inhaler`, `Drops`, `Other`), and default dose amount/unit.
- **Injectable & Vial Support**:
  - Administration route (`IM`, `SC`, `ID`, `IV`).
  - Vial concentration (`mg/mL`) and vial volume (`mL`).
  - Automatic draw volume (`mL`) and `U-100` insulin syringe unit calculation from `mg` or `mcg` doses.
  - Separate draw needle and injection needle specifications (`Gauge × Length`) plus syringe size/description.
- **OpenFDA NDC-11 & Barcode / 2D DataMatrix Scanner**:
  - Scan **2D GS1 DataMatrix** (`(01)` GTIN-14 + `(17)` Expiration Date + `(10)` Lot Number), **1D UPC-A / EAN-13** retail drug barcodes, or enter a 10- or 11-digit National Drug Code (`NDC-11`).
  - Automatically queries the [OpenFDA Drug NDC API](https://open.fda.gov/apis/drug/ndc/) across all 10- and 11-digit segment permutations (`4-4-2`, `5-3-2`, `5-4-1`) and populates formula name, brand name, manufacturer, strength, dosage form, route, vial concentration/volume, lot number, and expiration date.
- **Medication-Level Safety & Pacing Guardrails**:
  - Minimum interval between doses (`minIntervalMinutes`).
  - Maximum daily dose count (`maxDailyDoses`) and maximum daily cumulative amount (`maxDailyAmount`).
  - Real-time pacing warnings on the **Today** board across both scheduled and PRN doses.

### 2. Automated Anatomical Site Rotation
- Configurable per-medication ordered site rotation lists for injectables and topicals, with 1-tap clinical presets (e.g., *Ventrogluteal L/R*, *Deltoid L/R*, *Vastus Lateralis L/R*, *6-Site IM Rotation*, *Abdomen Quadrants*, *Topical Arms/Shoulders*).
- **Actual-Site Continuation**: Next suggested site automatically advances from the **actual site used** in the most recent `TAKEN` dose log—so overriding a site on a given week cleanly shifts subsequent weeks without double-injecting the same side.

### 3. Low-Friction Dose Windows & Escalating Alarms
- **Time-of-Day Windows**: Instead of brittle single-minute reminders, doses are grouped into customizable **Dose Windows** (e.g., *Morning `7:00 AM – 10:00 AM`*, *Afternoon `12:00 PM – 3:00 PM`*, *Evening `5:00 PM – 8:00 PM`*, *Bedtime `9:00 PM – 11:30 PM`*).
- **Two-Stage Doze-Piercing Notification & Exact Alarm Engine**:
  1. **Window Start (Persistent Notification)**: Uses exact Doze-piercing wakeup (`setAlarmClock` / `setExactAndAllowWhileIdle`) to pin an ongoing notification on the exact start minute of a window with 1-tap **Take (`<Suggested Site>`)**, **Snooze 30m**, and **Skip** actions (and immediately syncs if a window is already open).
  2. **Window End (Insistent Alarm)**: If no action (`TAKEN` or `SKIPPED`) is logged by the end of the window, triggers a high-priority insistent alarm (`Notification.FLAG_INSISTENT` + `USAGE_ALARM`).
- **Flexible Schedules**:
  - Every Day (`DAILY`)
  - Every *N* Days Fixed Cycle (`EVERY_N_DAYS`)
  - Specific Days of Week (`DAYS_OF_WEEK`)
  - Every *N* Days Relative to Last Taken Dose (`RELATIVE_TO_LAST_DOSE`) — prevents schedule drift when a weekly/biweekly dose is taken a day late
  - As Needed / PRN (`AS_NEEDED`)
- **Batch Actions**: 1-tap **"Take All"** per time-of-day window on the Today screen.

### 4. Prescribers & Conditions
- **Prescribers**: Track doctor name, specialty, practice/clinic, phone, email, Primary Care (`PCP`) status, start/end of care dates, and view all linked medications.
- **Conditions (Indications)**: Track diagnoses, clinical status (`Active`, `Managed / Stable`, `In Remission`, `Resolved`), diagnosis date, notes, and link conditions directly as medication indications.

### 5. Global Settings, Privacy & Adaptive Multi-Form-Factor UI
- **Global Notification & Alarm Controls**:
  - Master switch to pause/resume all dose window notifications and alarms globally.
  - Separate master switch to silence end-of-window ringing alarms while keeping persistent window-start notifications active.
  - **Discreet Notifications**: Optional lock-screen & notification shade privacy mode (`VISIBILITY_PRIVATE`) that hides medication names and anatomical injection sites.
  - Configurable default snooze duration (`15m`, `30m`, `45m`, `60m`) and optional confirmation prompt before 1-tap dose logging.
- **Appearance & Theme Controls**:
  - Instant switching between `System Default`, `Light`, and `Dark` themes plus **Material You Dynamic Color** support.
- **Adaptive Navigation & List-Detail Panes**:
  - Automatically switches between a 4-tab bottom navigation bar (compact phones) and navigation rail + two-pane list/detail layouts (unfolded foldables, tablets, and desktop windows) using `NavigationSuiteScaffold` and `ListDetailPaneScaffold`.
- **100% Architecture-Neutral Local Storage**:
  - Built on Android's native `SQLiteOpenHelper` + `kotlinx-serialization-json` with zero native `.so` binary dependencies—ensuring out-of-the-box compatibility across `arm64-v8a`, `x86_64` Chromebooks/desktops, and 16 KB page size devices.
  - Full JSON backup export/restore (including settings) and 1-tap sample data cleanup via Settings.

---

## Project Structure

```text
app/src/main/kotlin/com/exaclast/regimen/
├── MainActivity.kt                  # Entry point & edge-to-edge host
├── RegimenApp.kt                    # Adaptive NavigationSuiteScaffold & live theme host
├── data/
│   ├── DrugBarcodeScanner.kt        # Google Code Scanner wrapper for 2D GS1 DataMatrix & UPC
│   ├── FdaNdcLookupService.kt       # OpenFDA NDC-11 lookup, GS1/UPC barcode parser & string parser
│   ├── RegimenDatabase.kt           # SQLite storage engine
│   └── RegimenRepository.kt         # Reactive StateFlow repository, settings store & demo seeding
├── domain/
│   └── DoseEngine.kt                # Schedule evaluation, site rotation, and pacing engine
├── model/
│   └── Models.kt                    # Serializable domain entities, AppSettings & backup bundle
├── notifications/
│   ├── DoseActionReceiver.kt        # BroadcastReceiver for 1-tap Take/Snooze/Skip & exact alarm chaining
│   └── DoseNotificationManager.kt   # Doze-piercing persistent window notifications & insistent alarms
└── ui/
    ├── RegimenViewModel.kt          # UI state holder & user actions
    ├── screens/
    │   ├── TodayScreen.kt           # Daily dose board, PRN drawer, and dose confirmation dialog
    │   ├── MedicationsScreen.kt     # Adaptive medication list/detail, streamlined editor, schedule dialogs
    │   ├── PrescribersScreen.kt     # Prescriber directory & editor
    │   ├── ConditionsScreen.kt      # Condition/indication tracker & editor
    │   └── SettingsDialog.kt        # Global notifications/alarms, appearance, dose windows, backup & reset
    └── theme/                       # Material 3 color scheme & typography
```

---

## Building & Running Locally

### Prerequisites
- **JDK 17+**
- **Android SDK Platform 36** (`compileSdk = 36`, `minSdk = 26`)

### Commands
```zsh
# Run unit tests (DoseEngine & OpenFDA NDC-11 parser tests)
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK & AAB
./gradlew assembleRelease bundleRelease
```

---

## CI/CD & Automated Signing (GitHub Actions)

The repository includes [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml):
- **Push / PR to `main`**: Runs unit tests and uploads `regimen-debug-apk` as a workflow artifact.
- **Tag Push (`v*.*.*`) or Manual Workflow Dispatch**: Runs unit tests, signs the release APK and AAB, uploads release artifacts, and publishes a GitHub Release.

### Signing Setup
1. Generate a PKCS#12 keystore:
   ```zsh
   keytool -genkeypair -v -keystore release.keystore -alias regimen -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode the keystore:
   ```zsh
   base64 -i release.keystore | tr -d '\n' | pbcopy
   ```
3. Add repository secrets under **Settings → Secrets and variables → Actions**:
   - `SIGNING_KEYSTORE_BASE64`: Base64-encoded keystore string
   - `SIGNING_KEYSTORE_PASSWORD`: Keystore password
   - *(Optional)* `SIGNING_KEY_ALIAS`: Defaults to `regimen`
   - *(Optional)* `SIGNING_KEY_PASSWORD`: Defaults to `SIGNING_KEYSTORE_PASSWORD`
4. Push a version tag to trigger a signed release:
   ```zsh
   git tag v1.0.0 && git push origin v1.0.0
   ```

