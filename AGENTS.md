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

- **UI Framework**: Modern Jetpack Compose with Material 3 and adaptive layout components (`NavigationSuiteScaffold`).
- **State Management**: Unidirectional Data Flow (UDF) through `RegimenViewModel` exposing `uiState: StateFlow<RegimenUiState>`.
- **Navigation & Back Handling**:
  - Predictive back gestures are enabled via `android:enableOnBackInvokedCallback="true"`.
  - Secondary top-level destinations (Medications, Prescribers, Conditions) use `BackHandler` to smoothly navigate back to the `Today` screen instead of exiting the app.
  - Dialogs and sub-flows (e.g., editing dose windows, JSON import) must intercept the back gesture before closing the parent container.
- **Prescription Label Parsing**:
  - On-device label extraction (`DrugBarcodeScanner.kt` and `DrugLabelParser.kt`) utilizes schema decoy fields (`patientName`, `pharmacyName`, `rxNumber`) and post-processing fuzzy token sanitization to ensure patient and prescriber names are never parsed as medication brand names.
- **Dose Windows (Buckets)**:
  - Windows can be added, deleted, edited, and reordered by the user.
  - Windows can cross midnight (`crossesMidnight = true`). Receivers and alarm scheduling must account for overnight dates when querying scheduled items.

