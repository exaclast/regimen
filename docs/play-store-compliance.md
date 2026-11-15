# Google Play Store Compliance & Data Safety Reference

Reference for Play Console declarations, restricted permissions, and Data Safety answers for **Regimen** (`com.exaclast.regimen`). Audited against the v1.0 codebase (`minSdk` 35, `targetSdk` 37).

---

## 1. Restricted & Special Permissions

### Removed: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **Status:** Removed from `AndroidManifest.xml` and `StartupPermissionsDialog` (`T-056`).
- **Why:** Google Play restricts `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to narrow exempt categories (not medication or reminder apps). Regimen schedules exact alarms using `AlarmManager.setAlarmClock` (for end-of-window alarms and snoozes) and `AlarmManager.setExactAndAllowWhileIdle` (for window start and repeat nudges), which wake the device through Doze without a battery optimization exemption. Automatic local backups run via `WorkManager`.

### Kept: `USE_FULL_SCREEN_INTENT`
- **Where used:** `DoseNotificationManager.postWindowNotification` (`DoseNotificationStage.WINDOW_END_ALARM` -> `DoseAlarmActivity`).
- **Play Console justification:**
  - Time-critical end-of-window medication alarm (`CATEGORY_ALARM`, `AudioAttributes.USAGE_ALARM`, `AlarmManager.setAlarmClock`).
  - Fires only when a scheduled dose window expires with unlogged doses and the user has enabled End-of-Window Alarms (`AppSettings.alarmsEnabled`, `TimeOfDayBucket.alarmAtWindowEnd`).
  - Opens `DoseAlarmActivity` over the lock screen (`showWhenLocked="true"`, `turnScreenOn="true"`) with Snooze, Dismiss, and Take controls; respects discreet lock-screen mode (`discreetNotifications`).
- **Fallback behavior if denied or revoked:**
  - `DoseNotificationManager.canUseFullScreenIntent()` checks `NotificationManager.canUseFullScreenIntent()`.
  - If Play does not auto-grant full-screen intent (standard for non-Clock apps on Android 14+), the end-of-window alarm falls back gracefully to a high-priority heads-up alarm notification, and Today/Settings surfaces a notice linking to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.

### Kept: `SCHEDULE_EXACT_ALARM`
- **Where used:** `DoseNotificationManager.scheduleExactWakeup` (`setAlarmClock` / `setExactAndAllowWhileIdle`), listening to `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` in `DoseActionReceiver`.
- **Play Console declaration:**
  - **Use case:** Medication & health reminders — exact dose window opening/closing alarms, repeat nudges, and minimum-interval snooze wake-ups.
  - Note: Regimen uses `SCHEDULE_EXACT_ALARM` (user-grantable), **not** `USE_EXACT_ALARM`, and falls back safely to `setAndAllowWhileIdle` if revoked.

### Transitive Permissions from Jetpack / ML Kit
- `com.google.android.apps.aicore.service.BIND_SERVICE` (`mlkit:genai-prompt`): binds on-device Gemini Nano via AICore.
- `android.permission.ACCESS_NETWORK_STATE` & `android.permission.FOREGROUND_SERVICE` (`androidx.work:work-runtime-ktx` / ML Kit transport): merged from Jetpack WorkManager's `SystemForegroundService` (Regimen does not start foreground services itself) and `datatransport`.

---

## 2. App Access & User Accounts

- **Account creation / login:** None. Regimen has no authentication, registration, remote user accounts, or gated features.
- **Reviewer demo credentials:** Not required (all features work immediately offline; `Settings -> Backup -> Load demo data` is also available for testing).
- **Account deletion link:** Not required (no user accounts or remote server records exist; all local data can be wiped via `Settings -> Backup -> Delete all data`).

---

## 3. Data Safety Form Answers

### Summary
- **Does your app collect or share any of the required user data types?**
  - **First-party developer backend:** **No.** Exaclast / Regimen operates no backend servers and transmits zero user data to developer-controlled infrastructure.
  - **Third-party / optional external requests:**
    1. **OpenFDA Drug Lookup (`FdaNdcLookupService`, optional opt-in):** Off by default (`AppSettings.allowFdaLookup = false`). Only executes when the user enables OpenFDA Lookup (in Setup or `Settings -> Scanning & Lookup`, which displays an explicit disclosure of what is sent) and taps scan/match/search. Sends only drug query parameters (drug name, strength, or NDC) over HTTPS (`https://api.fda.gov/drug/ndc.json`) — never patient name, dose logs, indications, or providers. Response is ephemeral lookup data (not stored on a developer server).
    2. **Google ML Kit SDKs (`com.google.mlkit:text-recognition`, `com.google.mlkit:genai-prompt`):** Label OCR (`LabelTextRecognizer`) and Gemini Nano extraction (`DrugBarcodeScanner`) run strictly on-device. Captured label photos are stored transiently in `cache/label_scans/` and deleted immediately after scanning or on discard/startup (`T-050`). However, Google's bundled `mlkit:text-recognition` / `datatransport` runtime may transmit basic SDK diagnostics/telemetry (library version, device/API latency/status) to Google Play services unless disabled; verify Google's ML Kit Play Data Safety guidance and declare **App info and performance -> Diagnostics** as collected ephemerally/in-transit by the ML Kit SDK if required by the Play Console SDK check, while clarifying in the privacy policy that label images and medication text never leave the device.
    3. **User-initiated exports & sharing (Exempt):**
       - `CareSummaryScreen` uses Android Sharesheet (`Intent.ACTION_SEND`, `text/plain`) only when the user taps Share.
       - Encrypted backups (`.regimen`, AES-256-GCM + PBKDF2-SHA256) and optional plain JSON exports write strictly to a user-selected Storage Access Framework URI (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`).
       - Android Auto Backup (`backup_rules.xml`, `data_extraction_rules.xml`) explicitly excludes `regimen_store.db*`, `regimen_backup_keys.xml`, and `regimen_auto_backup.xml`.

### Suggested Play Console Data Safety Matrix
| Question | Answer | Notes |
|----------|--------|-------|
| Does your app collect or share any required user data types? | **Yes** *(only if declaring ML Kit SDK diagnostics)* or **No** *(if OpenFDA ephemeral drug queries & ML Kit local processing are classified as exempt)* | **Important:** Play Console's automated SDK scanner often checks `com.google.mlkit:text-recognition` (`datatransport-backend-cct`) for **App info and performance -> Diagnostics**. Declare ML Kit SDK diagnostics if flagged in Play Console pre-launch / Data Safety checks. |
| Is all data encrypted in transit? | **Yes** | `FdaNdcLookupService` uses `https://api.fda.gov/...` (TLS); ML Kit transport uses HTTPS. |
| Do you provide a way for users to request that their data be deleted? | **No account to delete** (in-app `Delete all data` wipes local DB, prefs, and keys). | No user accounts or server-side user data exist. |
| Independent security review | Optional | Client-side AES-256-GCM encryption for all backups. |

---

## 4. Pre-Submission Play Console Checklist

1. **Privacy Policy URL:** Because the app declares `INTERNET` and handles health/medication tracking data, Play Console requires a public Privacy Policy URL on the store listing and linked consistently with the app's disclosures:
   - State clearly that all medication lists, schedules, dose logs, indications, providers, and optional patient name stay on-device (or in user-controlled AES-GCM encrypted backups).
   - Explain optional OpenFDA NDC lookup (off by default; sends only drug name/strength/NDC to `api.fda.gov`).
   - Explain on-device ML Kit OCR & Gemini Nano label scanning (photos and text are processed on-device and photos are deleted immediately; ML Kit SDK may send anonymous performance/initialization diagnostics).
2. **Health Apps Declaration:** Complete the Play Console **Health** content declaration (Medication & pain management / Medication reminder & adherence tracking; include a standard disclaimer that Regimen is a personal tracking tool and not a substitute for professional medical advice).
3. **App Content -> Full-screen intent & Exact alarms:** Submit the justifications in Section 1 above.
4. **Target API & Build:** `targetSdk 37`, `minSdk 35`, `isMinifyEnabled = true`, `isShrinkResources = true` (with Play App Signing / release keystore).
