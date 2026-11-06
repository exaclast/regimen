package com.exaclast.regimen.data

import android.content.Context
import android.net.Uri
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.sync.BundleCrypto
import com.exaclast.regimen.sync.MergeConflict
import java.time.LocalDate

/**
 * Encrypted backup files: passphrase setup, export, and restore (merge or replace).
 * All methods block (key derivation and file I/O); call them off the main thread.
 */
class BackupManager(context: Context, private val repository: RegimenRepository) {

  private val appContext = context.applicationContext
  val keys = BackupKeyStore(appContext)

  val isEncryptionConfigured: Boolean
    get() = keys.isConfigured

  // region Passphrase

  /** First-time setup: a new data key locked with [passphrase]. Returns the recovery code. */
  fun setUpEncryption(passphrase: CharArray): String {
    val dataKey = BundleCrypto.newDataKey()
    keys.save(dataKey, BundleCrypto.wrapWithPassphrase(dataKey, passphrase))
    return BundleCrypto.recoveryCode(dataKey)
  }

  /** Re-locks the same data key with a new passphrase, so earlier backups stay readable. */
  fun changePassphrase(current: CharArray, new: CharArray) {
    val envelope = keys.envelope() ?: error("Encryption isn't set up")
    val dataKey = BundleCrypto.unwrapWithPassphrase(envelope, current)
    keys.save(dataKey, BundleCrypto.wrapWithPassphrase(dataKey, new))
  }

  /** Shows the recovery code after confirming the passphrase. */
  fun recoveryCode(passphrase: CharArray): String {
    val envelope = keys.envelope() ?: error("Encryption isn't set up")
    return BundleCrypto.recoveryCode(BundleCrypto.unwrapWithPassphrase(envelope, passphrase))
  }

  /**
   * Starts over with a new passphrase and data key when the old passphrase is forgotten.
   * Earlier backups then need their old passphrase or recovery code.
   */
  fun resetEncryption(passphrase: CharArray): String {
    keys.clear()
    return setUpEncryption(passphrase)
  }

  // endregion

  // region Export

  fun suggestedFileName(encrypted: Boolean, today: LocalDate = LocalDate.now()): String =
    "Regimen backup $today." + if (encrypted) BackupFiles.FILE_EXTENSION else "json"

  /** Encrypted export of everything on this device. */
  fun exportEncrypted(uri: Uri) {
    val dataKey = keys.dataKey() ?: error("Set a backup passphrase first")
    val file = BundleCrypto.encrypt(BackupFiles.encode(repository.currentBundle()), dataKey, keys.envelope())
    write(uri, file)
  }

  /** Unencrypted, human-readable JSON (for inspection or other tools). */
  fun exportPlainJson(uri: Uri) {
    write(uri, BackupFiles.encode(repository.currentBundle(), pretty = true))
  }

  private fun write(uri: Uri, bytes: ByteArray) {
    val stream = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
    stream.use { it.write(bytes) }
  }

  // endregion

  // region Restore

  sealed interface OpenResult {
    data class Ready(val bundle: RegimenBackupBundle, val preview: BackupFiles.Preview, val wasEncrypted: Boolean) : OpenResult
    /** Encrypted with a different key: ask for its passphrase or recovery code. */
    data class NeedsUnlock(val file: ByteArray, val header: BundleCrypto.Header) : OpenResult
  }

  fun open(uri: Uri): OpenResult {
    val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      ?: error("Couldn't read the file")
    return when (val opened = BackupFiles.open(bytes)) {
      is BackupFiles.Opened.Plain -> ready(opened.bundle, wasEncrypted = false)
      is BackupFiles.Opened.Encrypted -> {
        val deviceKey = keys.dataKey()?.takeIf { BundleCrypto.keyId(it) == opened.header.keyId }
        if (deviceKey != null) {
          ready(BackupFiles.decodeBundle(BundleCrypto.decrypt(opened.file, deviceKey)), wasEncrypted = true)
        } else {
          OpenResult.NeedsUnlock(opened.file, opened.header)
        }
      }
    }
  }

  /**
   * Unlocks with the file's passphrase or recovery code. If this device has no key yet (a new
   * phone), it adopts the file's key and passphrase so its own backups match.
   */
  fun unlock(locked: OpenResult.NeedsUnlock, secret: String, isRecoveryCode: Boolean): OpenResult.Ready {
    val dataKey: ByteArray
    val plaintext: ByteArray
    if (isRecoveryCode) {
      dataKey = BundleCrypto.parseRecoveryCode(secret)
      plaintext = BundleCrypto.decrypt(locked.file, dataKey)
    } else {
      val (plain, key) = BundleCrypto.decryptWithPassphrase(locked.file, secret.toCharArray())
      plaintext = plain
      dataKey = key
    }
    val envelope = locked.header.envelope
    if (!keys.isConfigured && envelope != null) keys.save(dataKey, envelope)
    return ready(BackupFiles.decodeBundle(plaintext), wasEncrypted = true)
  }

  private fun ready(bundle: RegimenBackupBundle, wasEncrypted: Boolean) =
    OpenResult.Ready(bundle, BackupFiles.preview(repository.currentBundle(), bundle), wasEncrypted)

  data class RestoreOutcome(val conflicts: List<MergeConflict>, val previous: RegimenBackupBundle)

  /** Applies a backup. [RestoreOutcome.previous] is this device's data beforehand, for Undo. */
  fun restore(bundle: RegimenBackupBundle, replace: Boolean): RestoreOutcome {
    val before = repository.currentBundle()
    if (replace) {
      repository.applyBundle(bundle, replace = true)
      return RestoreOutcome(emptyList(), before)
    }
    val merged = BackupFiles.mergeIntoDevice(before, bundle, repository.deviceIdentity.deviceId)
    repository.applyBundle(merged.bundle, replace = false)
    return RestoreOutcome(merged.conflicts, before)
  }

  /** Puts back exactly what was on the device before a restore. */
  fun undoRestore(previous: RegimenBackupBundle) = repository.applyBundle(previous, replace = true)

  // endregion

  companion object {
    @Volatile
    private var INSTANCE: BackupManager? = null

    fun getInstance(context: Context): BackupManager = INSTANCE ?: synchronized(this) {
      INSTANCE ?: BackupManager(context, RegimenRepository.getInstance(context)).also { INSTANCE = it }
    }
  }
}
