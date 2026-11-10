# Design: Automatic Backups and Sync

Covers T-093 (local backups), T-102 (Google Drive) and T-103 (second destination). Tasks and priorities are in
`../BACKLOG.md`; settled choices are in `../DECISIONS.md`.

**Before starting either:** T-057–059 (validation and integrity). T-102 also needs T-060–065 (threading and the sync-ready
data model).

Shipped already: the bundle format, three-way merge, encryption, and manual encrypted export and import.

---

## Automatic local backups (T-093)

Goal: hands-off encrypted backups to a folder the user picks, so data survives a lost phone even without Drive.

- **Folder:** Settings → Backup → "Automatic backups": pick a folder via `ACTION_OPEN_DOCUMENT_TREE`, then
  `takePersistableUriPermission`.
  - Store the tree URI in device-only prefs and exclude it from Android Auto Backup, like `regimen_device`.
  - If the permission is revoked or the folder disappears, show a warning and stop.
- **Frequency:** daily or weekly (default daily).
- **Retention:** keep the last N (default 10), plus the newest file of each month for 12 months. Only prune files this app
  created (name pattern `Regimen backup <date>.regimen`).
- **Scheduling:** WorkManager `PeriodicWorkRequest` (unique work, `KEEP`), battery not low. Optionally a debounced one-off
  run after significant changes if the last backup is older than the period.
- **Needs encryption set up.** If no passphrase is set, the toggle routes to `BackupEncryption(SETUP)` first. Writing uses
  `BackupKeyStore.dataKey()`. If the Keystore key is lost, surface "Re-enter your backup passphrase".
- **Status row:** "Last backup: today 3:12 AM · 7 kept" and "Back up now". Store last success and failure in device prefs.
- **Dependency:** add `androidx.work:work-runtime-ktx` (not in the project yet). Sync needs it too.
- **Reuse:** `BackupManager.exportEncrypted` should take an `OutputStream` or `DocumentFile`, not just a `Uri`.
- **Tests:** retention selection as a pure function (dates in → files to delete).

## Google Drive sync (T-102)

- **Setup (user side):** a Google Cloud project with an Android OAuth client (package plus debug and release SHA-1s; a
  `.debug` id from T-086 needs its own client). Scope `drive.appdata` (hidden app folder, non-sensitive). A desktop or web
  viewer would be another client in the same project.
- **Auth:** Identity Services `AuthorizationClient` with `drive.appdata`; Drive REST v3 over HTTP. Avoid the heavy Drive
  client library if possible.
- **Remote layout:** `regimen-sync.regimen` (encrypted bundle) in `appDataFolder`. Upload with ETag/`If-Match` (or a
  `modifiedTime` check) to detect concurrent writers. On a conflict: re-download, re-merge, retry.
- **Local merge base:** store the last-synced bundle, encrypted, in app storage as the base for `BundleMerger.merge`.
- **Sync loop:**
  1. Download the remote copy.
  2. Merge `base`, `local` and `remote`.
  3. If `localChanged`, apply with `repository.applyBundle(merged, replace = false)`.
  4. If `remoteChanged`, upload.
  5. Save the merged copy as the new base.
  6. Update this device's `lastSyncedEpochMillis` in `devices`.
- **Triggers:** see Sync in `../DECISIONS.md`. WorkManager unique work for push (debounce with `REPLACE` and an initial
  delay) and for pull.
- **Device list (Settings → Sync):** name, model, last synced, Primary badge. "Make this device primary": pull first, then
  write a `PrimaryClaim` with epoch + 1. Rename via `DeviceIdentity.deviceName`.
- **Reminders on non-primary devices:** a per-device switch, default off. Gate `DoseNotificationManager.refresh` scheduling
  on it.
- **New device flow:** sign in, find the remote file, unlock with the passphrase, recovery code, or a QR scan of the code
  shown on the primary (`zxing-core` to generate; CameraX and ML Kit barcode to scan).
- **Conflict note:** a Snackbar or Settings row, e.g. "Merged a dose logged on Pixel 8a", from `MergeResult.conflicts`.
- **Sharding:** only when the single file gets large.

### What the data model must do first (T-061–065)

- Soft-delete dose windows. `applyBundle` and `deleteBucket` hard-delete today.
- Store the primary claim and device list. `currentBundle` sends `primary = null`, only this device in `devices`, and
  `lastSyncedEpochMillis = 0`.
- Make merge ties converge. In `BundleMerger.decide`, equal timestamps make each device choose the other's copy.
- Stop "Delete all data" and "Load demo data" erasing tombstones, and give demo records unique IDs.
- Handle clock skew.

## Second destination (T-103)

Abstract a `SyncTransport` (download, upload with a version check) once Drive works. Candidates: WebDAV (covers Nextcloud)
or a self-hosted endpoint.

---

## Key files

| Area | Files |
|------|-------|
| Format and merge | `sync/BundleMerger.kt`, `sync/BackupFiles.kt`, `model/Models.kt` (`RegimenBackupBundle`, `SharedSettings`, `SyncDevice`, `PrimaryClaim`) |
| Encryption and keys | `sync/BundleCrypto.kt`, `data/BackupKeyStore.kt`, `sync/DeviceIdentity.kt` |
| Backup flow | `data/BackupManager.kt`, `ui/screens/BackupScreens.kt`, `RegimenViewModel` (Backup region), `SettingsScreen` (Backup section) |
| Repository hooks | `RegimenRepository.currentBundle()`, `applyBundle(bundle, replace)` |
| Tests | `sync/BundleMergerTest`, `BundleCryptoTest`, `BackupFilesTest` |
