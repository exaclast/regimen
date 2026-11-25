# Regimen (`com.exaclast.regimen`)

[![Android CI](https://github.com/exaclast/regimen/actions/workflows/android-release.yml/badge.svg?branch=main)](https://github.com/exaclast/regimen/actions/workflows/android-release.yml)
[![Latest stable release](https://img.shields.io/github/v/release/exaclast/regimen?label=stable)](https://github.com/exaclast/regimen/releases/latest)
[![Latest pre-release](https://img.shields.io/github/v/release/exaclast/regimen?include_prereleases&label=pre-release)](https://github.com/exaclast/regimen/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Min SDK 35](https://img.shields.io/badge/minSdk-35-green)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)

**Regimen** is a low-friction native Android medication and dose-tracking app built with Kotlin, Jetpack Compose and Material 3 Expressive. It is designed for ADHD-friendly adherence and for complex injectable/topical protocols with automated anatomical site rotation. It adapts to phones, foldables, tablets and desktop windows (Chromebooks / Android Desktop).

---

## Key Features

### 1. Medication & Supplement Tracking
- **Medications & supplements (`RegimenItem`)**: separate **Medications** and **Supplements** tabs with tailored full-screen editors (`MedicationEditorScreen` and `SupplementEditorScreen`). Both track a **Prescribed** vs **OTC** source (defaulting to Prescribed for medications and OTC for supplements) and share scheduling, logging, adjustments, pacing, and optional provider/indication links.
- **Core details**:
  - **Medications**: formula/generic name, brand, strength, dosage form (`Tablet`, `Capsule`, `Softgel`, `Gummy`, `Chewable`, `Powder`, `Injectable (Vial)`, `Injectable (Pen)`, `Topical`, `Oral Liquid`, `Inhaler`, `Drops`, `Other`), and default dose amount/unit.
  - **Supplements**: vitamin/fiber/pre-workout name, optional strength, supplement-friendly forms (`SUPPLEMENT_FORMS`, excluding injectables) and units (`g`, `softgel`, `gummy`, `scoop`, `packet`, `tsp`, `tbsp`, plus standard units), optional brand/manufacturer and start date, and an editable list of **Key ingredients** (`KeyIngredient` name + amount).
- **Injectable & vial support**:
  - Administration route (`IM`, `SC`, `ID`, `IV`).
  - Vial volume (`mL`) with an automatic draw volume worked out from the strength (e.g. `200 mg/10 mL`, `200 units/mL`); there is no separate concentration field. Prefilled devices are dialed, so they show no volume.
  - Syringes are picked by type (U-100 or U-500 insulin, tuberculin, Luer-lock, Luer-slip, other) and a usual size. Only insulin syringes show unit markings. Separate draw and injection needle specs (`Gauge × Length`) show the standard ISO hub color for the gauge.
- **Dated changes (titration)**: record a dose, strength or equipment change from a date, such as an isotretinoin step-up or a new vial size, instead of overwriting history. Plan ahead from the detail page, or choose "From a date" or "Correction" when editing. Today, reminders and the alarm screen show each day's dose, and the first dose at a new amount is flagged. A change of strength or package clears the NDC until the FDA lookup supplies the new one.
- **On-device label scanner (Gemini Nano / AICore)**: available on medications. ML Kit text recognition reads one or more bottle/vial photos (a label that wraps around a small bottle can take a few), then the ML Kit GenAI Prompt API turns that text, or pasted text, into structured fields on-device. Only text reaches the model, because AICore's safety check refuses many label photos. The result includes fields (NDC-9/10/11, names, strength, form, manufacturer, lot, expiry, instructions). Decoy schema fields and token sanitization keep patient, pharmacy and prescriber names from being read as drug names. Scan buttons are disabled on devices without AICore; manual lookup works everywhere.
- **OpenFDA cross-reference**: a product **NDC-9** or package **NDC-11** (plus 10-digit/barcode formats) takes first priority in the [OpenFDA NDC API](https://open.fda.gov/apis/drug/ndc/). Otherwise name, strength, form and manufacturer are combined. A name that is not an exact match is never applied without asking, and ambiguous matches open a disambiguation dialog.
- **Organized lists**: separate **Medications** and **Supplements** tabs with search and grouping chips (`Schedule`, `Indication` when items in that tab link to active indications, or `Form`), alphabetical within each section. Finished items collapse out of the way.
- **Pacing guardrails**: minimum interval between doses, maximum daily dose count, and maximum daily amount (totaled in the default unit). Daily limits count a dose on the calendar day it was taken, with overnight-window exceptions. Warnings appear on Today, in notifications and on the alarm screen. Logging a dose over its limit happens in the app, which explains the limit first.

### 2. Anatomical Sites
- Every form with sites (injectables, topicals, inhalers/nasal, drops, other) has three modes: **Off**, **Record** (suggests the last site used) or **Rotate** (suggests the next in order). Only injectables default to Rotate.
- Per-medication ordered site lists with presets (e.g. *Ventrogluteal L/R*, *Deltoid L/R*, *Vastus Lateralis L/R*, *6-Site IM Rotation*, *Abdomen Quadrants*).
- **Actual-site continuation**: the next suggested site advances from the site actually used in the most recent taken dose, so an override shifts later doses cleanly.

### 3. Schedules & Dose Windows
- **Dose windows**: doses are grouped into customizable time-of-day windows (Morning, Afternoon, Evening, Bedtime by default). Windows can be added, edited and deleted, are always ordered by start time, and can cross midnight. After midnight, Today shows a still-open dose from yesterday as "From last night".
- **Deleting a window** lets you choose where its doses move; logs move with them so a taken dose isn't due again. "Restore default windows" keeps any custom window a schedule still uses.
- **Schedule types**: every day, every *N* days, specific days of the week, specific dates, every *N* days relative to the last dose (no drift when a dose is taken late), and as needed (PRN).
- **Course dates**: optional inclusive start and end dates, with a start/end window for partial first and last days. A dose that was actually logged always stays visible.
- **Logging**: 1-tap Take, Skip (with an optional skip-reason prompt), **Take all** per window, split **As-needed medications** / **As-needed supplements** sections on Today, and a "Log extra dose" picker (with Medications/Supplements tabs when both exist) for unscheduled doses. Completed dose logs can be edited or deleted from the dose log editor, and reversible actions show a Snackbar with **Undo**.

### 4. Notifications & Alarms
- **One notification per window occurrence** ("Morning dose" / "Morning medications") listing the drugs and doses, with Take / Skip (single) or Take all (several).
- **Alarm engine**: exact, Doze-piercing alarms for window start, repeat nudges on a fixed grid, and end of window (only while unlogged doses remain). All of it is re-armed after any data, settings, clock or permission change, including after reboot. After a reboot, doses still waiting in a window that opened or closed while the phone was off alert once.
- **Blocked reminders are flagged**: Today shows a Fix notice when notifications are denied, a channel is blocked, exact alarms are denied or full-screen intents are blocked, and opens the right settings page.
- **Snooze**: swiping a reminder away snoozes it; the alarm screen and the app also offer Snooze, and each snoozed dose gets its own alarm.
- **End-of-window alarm screen** opens via a full-screen intent and rings; opening it from a tap is silent.
- **Discreet mode** hides medication names on the lock screen.
- **Master switches** to pause all reminders or silence only the ringing alarm.

### 5. Care: Providers, Indications & Health Summary (optional)
- **Care tab**: combines **Indications** and **Providers** under a segmented switcher (falling back directly to whichever single screen is tracked, and hiding the tab when both are turned off).
- **Providers**: name, specialty, practice, phone, email, optional **NPI** (up to 10 digits), primary-care status, care dates, and linked medications, listed as regular, as needed, or past. A medication's provider is labelled *Prescriber*.
- **Indications**: why a medication or supplement is taken: `ACUTE`, `CHRONIC` or `PREVENTIVE` (such as PrEP or PEP), with optional **ICD-10** code (non-reaction kinds only), dates, severity (`Mild` to `Life-threatening`, or `Unsure`), and notes, listed by kind and searchable by name, ICD-10 code, or notes. Providers and indications are many-to-many, and so are medications and indications; both links are editable from either side. An indication can also be marked **Secondary to** underlying conditions (showing both "Secondary to X" and reverse "Secondary conditions"). A condition or symptom that isn't formally diagnosed can be marked *Suspected* and sits in its own section. **Allergies** (an immune reaction to a drug, food or something in the environment) and **drug intolerances** (a side effect you can't tolerate, which isn't an allergy) are two separate kinds, kept to what a provider or first responder would need to know. They record the trigger, severity and reaction, list by what they're to (drug allergies and intolerances together), and can link to the medication that treats them (recording only, with no warnings). Past indications are archived, and "Had this again" starts a new episode of a past acute or preventive one.
- **Health Summary**: a one-page overview for a provider or first responder (accessible from Today, Care, or the launcher's long-press shortcut), ordered with drug allergies and intolerances first, other allergies, current medications, **Supplements** (with bulleted key ingredients), recently taken finished or as-needed medications (within 90 days), indications (with ICD-10 codes when set), suspected conditions, recently ended indications, and active providers, shareable as plain text.
- Both Providers and Indications are optional: turn off either in Settings to hide its tab and fields. Hiding never deletes data.

### 6. Encrypted Backup & Sync Groundwork
- One format for backups and sync: a versioned JSON bundle, including deletion tombstones.
- **Encryption**: AES-256-GCM with a random data key wrapped by a PBKDF2 passphrase key. A recovery code (the data key plus a checksum) can be shown, and the data key is cached on the device under an Android Keystore key.
- **Automatic encrypted backups**: optional scheduled encrypted `.regimen` backups written via `WorkManager` to a chosen folder with configurable frequency and retention (keeping recent copies plus one per month for 12 months).
- **Merge-aware restore**: `BundleMerger` does a three-way merge against the last agreed copy. Replace restores this device's own settings too. Reminder, alarm, theme and FDA-consent settings stay per device.
- Each device has an identity, and a "primary" claim decides conflicts when both sides changed.

### 7. Settings, Privacy & Adaptive UI
- **First-launch checklist** for notifications, exact alarms, full-screen alarms, and optional tracking (Providers and Indications). Declined options aren't nagged again and stay available in Settings.
- **Appearance**: System / Light / Dark, plus Material You dynamic color.
- **Adaptive navigation**: `NavigationSuiteScaffold` switches between a bottom bar and a navigation rail, centering content columns cleanly on wide windows. Editors and settings are full-screen pages that hide the bar, with predictive back.
- **Local storage**: native `SQLiteOpenHelper` plus `kotlinx-serialization-json`, with no native `.so` dependencies. This works on `arm64-v8a`, `x86_64` and 16 KB page-size devices.

---

## Project Structure

```text
app/src/main/kotlin/com/exaclast/regimen/
├── MainActivity.kt, RegimenApp.kt       # Entry point, adaptive scaffold, Navigation 3 host, theme
├── data/                                # SQLite storage, repository, manual/auto backup + key store,
│                                        #   Gemini Nano label scanner/parser, OpenFDA lookup
├── domain/                              # DoseEngine, DoseTimeline, Adjustments, CareSummary, MedicationGroups,
│                                        #   ScheduleVersioning, DoseLogEdits, WindowDeletion, IndicationTimeline
├── model/                               # Models, MedicationAdjustment, Concentration, Equipment, Texts
├── notifications/                       # DoseNotificationManager, action receiver, alarm screen/ringtone
├── sync/                                # BundleCrypto, BundleMerger, BackupFiles, AutoBackupRetention, DeviceIdentity
└── ui/
    ├── RegimenViewModel.kt              # uiState: StateFlow + user actions
    ├── navigation/Routes.kt             # Serializable Routes + Navigator
    ├── components/                      # Shared lists, switches, pickers, UiFormat
    ├── screens/                         # Today, Medications & Supplements (list/detail/editors), Care,
    │                                    #   Providers, Indications, CareSummary, Adjustments/DoseLog editors, Settings, Backup screens
    └── theme/                           # Material 3 Expressive theme
```

Unit tests live in `app/src/test/kotlin/` and cover the dose engine and timeline, dated adjustments, care summary, medication groups, schedule versioning, dose log edits, concentration parsing, database migrations, window deletion, notifications text and timing, automatic backup retention/management, the bundle merger and crypto, label parsing and the FDA lookup.

---

## Building & Running Locally

### Prerequisites
- **JDK 17+**
- **Android SDK Platform 37** (`compileSdk = 37`, `targetSdk = 37`, `minSdk = 35`)
- AGP 9.1+ (Material 3 `1.5.0-rc01` requires it)

### Commands
```zsh
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK & AAB
./gradlew assembleRelease bundleRelease

# Install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.exaclast.regimen.debug/com.exaclast.regimen.MainActivity
```

---

## CI/CD & Automated Signing (GitHub Actions)

The repository includes [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml):
- **Push / PR to `main`**: runs unit tests and uploads `regimen-debug-apk` as a workflow artifact.
- **Tag push (`v*.*.*`)**: runs unit tests and lint, builds the signed release APK and AAB with `.sha256` sidecars, uploads them as workflow artifacts, and publishes a GitHub Release.
- **Pre-releases**: a tag containing a hyphen (e.g. `v0.4.0-alpha1`) is published as a pre-release; a plain tag (e.g. `v1.0.0`) is a stable release.

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

---

## License

Released under the [GNU GPL v3](LICENSE).
