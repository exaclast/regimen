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

### 1. Medication Tracking
- **Core details**: formula/generic name, brand, strength, dosage form (`Tablet`, `Capsule`, `Injectable (Vial)`, `Injectable (Pen)`, `Topical`, `Oral Liquid`, `Inhaler`, `Drops`, `Other`), and default dose amount/unit.
- **Injectable & vial support**:
  - Administration route (`IM`, `SC`, `ID`, `IV`).
  - Vial concentration (`mg/mL`) and volume (`mL`), with automatic draw volume. `U-100` syringe units are shown only on a U-100 syringe; prefilled devices show no volume.
  - Separate draw and injection needle specs (`Gauge × Length`) plus syringe size.
- **On-device label scanner (Gemini Nano / AICore)**: ML Kit text recognition reads one or more bottle/vial photos (a label that wraps around a small bottle can take a few), then the ML Kit GenAI Prompt API turns that text, or pasted text, into structured fields on-device. Only text reaches the model, because AICore's safety check refuses many label photos. The result includes fields (NDC, names, strength, form, manufacturer, lot, expiry, instructions). Decoy schema fields and token sanitization keep patient, pharmacy and prescriber names from being read as drug names. Scan buttons are disabled on devices without AICore; manual lookup works everywhere.
- **OpenFDA cross-reference**: an NDC takes first priority in the [OpenFDA NDC API](https://open.fda.gov/apis/drug/ndc/). Otherwise name, strength, form and manufacturer are combined. A name that is not an exact match is never applied without asking, and ambiguous matches open a disambiguation dialog.
- **Pacing guardrails**: minimum interval between doses, maximum daily dose count, and maximum daily amount (totaled in the default unit). Daily limits count a dose on the calendar day it was taken, with overnight-window exceptions. Warnings appear on Today, in notifications and on the alarm screen. Logging a dose over its limit happens in the app, which explains the limit first.

### 2. Anatomical Site Rotation
- Per-medication ordered site lists for injectables and topicals, with presets (e.g. *Ventrogluteal L/R*, *Deltoid L/R*, *Vastus Lateralis L/R*, *6-Site IM Rotation*, *Abdomen Quadrants*).
- **Actual-site continuation**: the next suggested site advances from the site actually used in the most recent taken dose, so an override shifts later doses cleanly.

### 3. Schedules & Dose Windows
- **Dose windows**: doses are grouped into customizable time-of-day windows (Morning, Afternoon, Evening, Bedtime by default). Windows can be added, edited and deleted, are always ordered by start time, and can cross midnight. After midnight, Today shows a still-open dose from yesterday as "From last night".
- **Deleting a window** lets you choose where its doses move; logs move with them so a taken dose isn't due again. "Restore default windows" keeps any custom window a schedule still uses.
- **Schedule types**: every day, every *N* days, specific days of the week, specific dates, every *N* days relative to the last dose (no drift when a dose is taken late), and as needed (PRN).
- **Course dates**: optional inclusive start and end dates, with a start/end window for partial first and last days. A dose that was actually logged always stays visible.
- **Logging**: 1-tap Take/Skip, **Take all** per window, and a separate "Log an extra dose" for unscheduled doses. Reversible actions show a Snackbar with **Undo** rather than a confirmation.

### 4. Notifications & Alarms
- **One notification per window occurrence** ("Morning dose" / "Morning medications") listing the drugs and doses, with Take / Skip (single) or Take all (several).
- **Alarm engine**: exact, Doze-piercing alarms for window start, repeat nudges on a fixed grid, and end of window (only while unlogged doses remain). All of it is re-armed after any data, settings, clock or permission change, including after reboot.
- **Snooze**: swiping a reminder away snoozes it; the alarm screen and the app also offer Snooze, and each snoozed dose gets its own alarm.
- **End-of-window alarm screen** opens via a full-screen intent and rings; opening it from a tap is silent.
- **Discreet mode** hides medication names on the lock screen.
- **Master switches** to pause all reminders or silence only the ringing alarm.

### 5. Providers & Indications (optional)
- **Providers**: name, specialty, practice, phone, email, primary-care status, care dates, and linked medications. A medication's provider is labelled *Prescriber*.
- **Indications**: why a medication is taken: `ACUTE`, `CHRONIC` or `PREVENTIVE` (such as PrEP or PEP), with dates and notes, listed by kind. Providers and indications are many-to-many, and so are medications and indications; both links are editable from either side. Past indications are archived, and "Had this again" starts a new episode of a past acute or preventive one.
- Both are optional: turn off either in Settings to hide its tab and fields. Hiding never deletes data.

### 6. Encrypted Backup & Sync Groundwork
- One format for backups and sync: a versioned JSON bundle, including deletion tombstones.
- **Encryption**: AES-256-GCM with a random data key wrapped by a PBKDF2 passphrase key. A recovery code (the data key plus a checksum) can be shown, and the data key is cached on the device under an Android Keystore key.
- **Merge-aware restore**: `BundleMerger` does a three-way merge against the last agreed copy. Replace restores this device's own settings too. Reminder, alarm, theme and FDA-consent settings stay per device.
- Each device has an identity, and a "primary" claim decides conflicts when both sides changed.

### 7. Settings, Privacy & Adaptive UI
- **First-launch checklist** for notifications, exact alarms, unrestricted battery and Gemini Nano status. Declined options aren't nagged again and stay available in Settings.
- **Appearance**: System / Light / Dark, plus Material You dynamic color.
- **Adaptive navigation**: `NavigationSuiteScaffold` switches between a bottom bar and a navigation rail. Editors and settings are full-screen pages that hide the bar, with predictive back.
- **Local storage**: native `SQLiteOpenHelper` plus `kotlinx-serialization-json`, with no native `.so` dependencies. This works on `arm64-v8a`, `x86_64` and 16 KB page-size devices.

---

## Project Structure

```text
app/src/main/kotlin/com/exaclast/regimen/
├── MainActivity.kt, RegimenApp.kt       # Entry point, adaptive scaffold, Navigation 3 host, theme
├── data/                                # SQLite storage, repository, backup + key store,
│                                        #   Gemini Nano label scanner/parser, OpenFDA lookup
├── domain/                              # DoseEngine (schedules, pacing, site rotation), DoseTimeline,
│                                        #   WindowDeletion, IndicationTimeline
├── model/Models.kt                      # Serializable entities, AppSettings, backup bundle
├── notifications/                       # DoseNotificationManager, action receiver, alarm screen/ringtone
├── sync/                                # BundleCrypto, BundleMerger, BackupFiles, DeviceIdentity
└── ui/
    ├── RegimenViewModel.kt              # uiState: StateFlow + user actions
    ├── navigation/Routes.kt             # Serializable Routes + Navigator
    ├── components/                      # Shared lists, switches, pickers, UiFormat
    ├── screens/                         # Today, Medications (list/detail/editor), Providers,
    │                                    #   Indications, Settings, Backup screens
    └── theme/                           # Material 3 Expressive theme
```

Unit tests live in `app/src/test/kotlin/` and cover the dose engine and timeline, window deletion, notifications text and timing, the bundle merger and crypto, label parsing and the FDA lookup.

---

## Building & Running Locally

### Prerequisites
- **JDK 17+**
- **Android SDK Platform 37** (`compileSdk = 37`, `targetSdk = 37`, `minSdk = 35`)
- AGP 9.1+ (Material 3 `1.5.0-beta02` requires it)

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
adb shell am start -n com.exaclast.regimen/.MainActivity
```

---

## CI/CD & Automated Signing (GitHub Actions)

The repository includes [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml):
- **Push / PR to `main`**: runs unit tests and uploads `regimen-debug-apk` as a workflow artifact.
- **Tag push (`v*.*.*`) or manual dispatch**: runs unit tests, builds the signed release APK and AAB, uploads them as workflow artifacts, and (for tags) publishes a GitHub Release.
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
