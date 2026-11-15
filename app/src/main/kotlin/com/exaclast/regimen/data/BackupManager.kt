package com.exaclast.regimen.data

import android.content.Context
import android.net.Uri
import com.exaclast.regimen.R
import com.exaclast.regimen.model.Message
import com.exaclast.regimen.model.MessageException
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

  val isEncryptionKeyLost: Boolean
    get() = keys.isKeyLost

  // region Passphrase

  /** First-time setup: a new data key locked with [passphrase]. Returns the recovery code. */
  fun setUpEncryption(passphrase: CharArray): String {
    val dataKey = BundleCrypto.newDataKey()
    return try {
      keys.save(dataKey, BundleCrypto.wrapWithPassphrase(dataKey, passphrase))
      BundleCrypto.recoveryCode(dataKey)
    } finally {
      passphrase.fill('\u0000')
      dataKey.fill(0)
    }
  }

  /** Re-locks the same data key with a new passphrase, so earlier backups stay readable. */
  fun changePassphrase(current: CharArray, new: CharArray) {
    val envelope = keys.envelope() ?: throw MessageException(Message(R.string.backup_err_not_set_up))
    var dataKey: ByteArray? = null
    try {
      dataKey = BundleCrypto.unwrapWithPassphrase(envelope, current)
      keys.save(dataKey, BundleCrypto.wrapWithPassphrase(dataKey, new))
    } finally {
      current.fill('\u0000')
      new.fill('\u0000')
      dataKey?.fill(0)
    }
  }

  /** Re-wraps the device's existing key envelope after the Android Keystore key was lost. */
  fun unlockDeviceKey(passphrase: CharArray) {
    val envelope = keys.envelope() ?: throw MessageException(Message(R.string.backup_err_not_set_up))
    var dataKey: ByteArray? = null
    try {
      dataKey = BundleCrypto.unwrapWithPassphrase(envelope, passphrase)
      keys.save(dataKey, envelope)
    } finally {
      passphrase.fill('\u0000')
      dataKey?.fill(0)
    }
  }

  /** Wraps an unlocked backup's [dataKey] with a new [passphrase] (after a recovery-code restore). */
  fun saveRecoveredKeyWithPassphrase(dataKey: ByteArray, passphrase: CharArray) {
    try {
      keys.save(dataKey, BundleCrypto.wrapWithPassphrase(dataKey, passphrase))
    } finally {
      passphrase.fill('\u0000')
      dataKey.fill(0)
    }
  }

  /** Shows the recovery code after confirming the passphrase. */
  fun recoveryCode(passphrase: CharArray): String {
    val envelope = keys.envelope() ?: throw MessageException(Message(R.string.backup_err_not_set_up))
    var dataKey: ByteArray? = null
    return try {
      dataKey = BundleCrypto.unwrapWithPassphrase(envelope, passphrase)
      BundleCrypto.recoveryCode(dataKey)
    } finally {
      passphrase.fill('\u0000')
      dataKey?.fill(0)
    }
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

  /** Encrypted export bytes of everything on this device (runs integrity checks first). */
  fun exportEncryptedBytes(): ByteArray {
    verifyExportIntegrity()
    val dataKey = keys.dataKey() ?: throw MessageException(
      Message(if (keys.isKeyLost) R.string.backup_err_key_lost else R.string.backup_err_set_passphrase_first),
    )
    return try {
      BundleCrypto.encrypt(BackupFiles.encode(repository.currentBundle()), dataKey, keys.envelope())
    } finally {
      dataKey.fill(0)
    }
  }

  /** Encrypted export of everything on this device. */
  fun exportEncrypted(outputStream: java.io.OutputStream) {
    val file = exportEncryptedBytes()
    outputStream.use { it.write(file) }
  }

  /** Encrypted export of everything on this device. */
  fun exportEncrypted(uri: Uri) {
    write(uri, exportEncryptedBytes())
  }

  /** Unencrypted, human-readable JSON (for inspection or other tools). */
  fun exportPlainJson(uri: Uri) {
    verifyExportIntegrity()
    write(uri, BackupFiles.encode(repository.currentBundle(), pretty = true))
  }

  private fun verifyExportIntegrity() {
    val unreadable = repository.undecodableRowCount()
    if (unreadable > 0) {
      throw MessageException(Message(R.plurals.backup_err_unreadable_records, unreadable, unreadable))
    }
  }

  private fun write(uri: Uri, bytes: ByteArray) {
    val stream = appContext.contentResolver.openOutputStream(uri, "wt") ?: throw MessageException(Message(R.string.backup_err_open_file))
    stream.use { it.write(bytes) }
  }

  // endregion

  // region Restore

  sealed interface OpenResult {
    data class Ready(
      val bundle: RegimenBackupBundle,
      val preview: BackupFiles.Preview,
      val wasEncrypted: Boolean,
      val unlockedWithRecoveryCode: Boolean = false,
      val unlockedDataKey: ByteArray? = null,
      val unlockedEnvelope: BundleCrypto.KeyEnvelope? = null,
    ) : OpenResult
    /** Encrypted with a different key: ask for its passphrase or recovery code. */
    data class NeedsUnlock(val file: ByteArray, val header: BundleCrypto.Header) : OpenResult
  }

  fun open(uri: Uri): OpenResult {
    val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
      readBounded(input)
    } ?: throw MessageException(Message(R.string.backup_err_read_file))
    return when (val opened = BackupFiles.open(bytes)) {
      is BackupFiles.Opened.Plain -> ready(opened.bundle, wasEncrypted = false)
      is BackupFiles.Opened.Encrypted -> {
        val deviceKey = keys.dataKey()?.takeIf { BundleCrypto.keyId(it) == opened.header.keyId }
        if (deviceKey != null) {
          val plain = try {
            BundleCrypto.decrypt(opened.file, deviceKey)
          } finally {
            deviceKey.fill(0)
          }
          ready(BackupFiles.decodeBundle(plain), wasEncrypted = true)
        } else {
          OpenResult.NeedsUnlock(opened.file, opened.header)
        }
      }
    }
  }

  private fun readBounded(input: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read == -1) break
      total += read
      if (total > BundleCrypto.MAX_FILE_BYTES) throw BundleCrypto.FileTooLargeException()
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  /**
   * Unlocks with the file's passphrase or recovery code. Key adoption is deferred until [restore]
   * is confirmed: previewing and cancelling leaves this device's key configuration untouched.
   */
  fun unlock(locked: OpenResult.NeedsUnlock, secret: String, isRecoveryCode: Boolean): OpenResult.Ready {
    val dataKey: ByteArray
    val plaintext: ByteArray
    if (isRecoveryCode) {
      dataKey = BundleCrypto.parseRecoveryCode(secret)
      plaintext = BundleCrypto.decrypt(locked.file, dataKey)
    } else {
      val pass = secret.toCharArray()
      try {
        val (plain, key) = BundleCrypto.decryptWithPassphrase(locked.file, pass)
        plaintext = plain
        dataKey = key
      } finally {
        pass.fill('\u0000')
      }
    }
    return ready(
      bundle = BackupFiles.decodeBundle(plaintext),
      wasEncrypted = true,
      unlockedWithRecoveryCode = isRecoveryCode,
      unlockedDataKey = dataKey,
      unlockedEnvelope = locked.header.envelope,
    )
  }

  private fun ready(
    bundle: RegimenBackupBundle,
    wasEncrypted: Boolean,
    unlockedWithRecoveryCode: Boolean = false,
    unlockedDataKey: ByteArray? = null,
    unlockedEnvelope: BundleCrypto.KeyEnvelope? = null,
  ) = OpenResult.Ready(
    bundle = bundle,
    preview = BackupFiles.preview(repository.currentBundle(), bundle),
    wasEncrypted = wasEncrypted,
    unlockedWithRecoveryCode = unlockedWithRecoveryCode,
    unlockedDataKey = unlockedDataKey,
    unlockedEnvelope = unlockedEnvelope,
  )

  data class RestoreOutcome(
    val conflicts: List<MergeConflict>,
    val previous: RegimenBackupBundle,
    /** Set when a recovery-code unlock on an unconfigured device needs a new passphrase. */
    val recoveryDataKeyForNewPassphrase: ByteArray? = null,
  )

  /** Applies a backup. [RestoreOutcome.previous] is this device's data beforehand, for Undo. */
  fun restore(
    bundle: RegimenBackupBundle,
    replace: Boolean,
    ready: OpenResult.Ready? = null,
  ): RestoreOutcome {
    val before = repository.currentBundle()
    val outcomeConflicts = if (replace) {
      repository.applyBundle(bundle, replace = true)
      emptyList()
    } else {
      val merged = BackupFiles.mergeIntoDevice(before, bundle, repository.deviceIdentity.deviceId)
      repository.applyBundle(merged.bundle, replace = false)
      merged.conflicts
    }

    var recoveryKeyForNewPassphrase: ByteArray? = null
    val key = ready?.unlockedDataKey
    if (key != null) {
      if (!keys.isConfigured) {
        if (ready.unlockedWithRecoveryCode) {
          // Don't adopt the old file's passphrase: ask the user to set a new one.
          recoveryKeyForNewPassphrase = key
        } else if (ready.unlockedEnvelope != null) {
          try {
            keys.save(key, ready.unlockedEnvelope)
          } finally {
            key.fill(0)
          }
        } else {
          key.fill(0)
        }
      } else {
        key.fill(0)
      }
    }
    return RestoreOutcome(outcomeConflicts, before, recoveryKeyForNewPassphrase)
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
