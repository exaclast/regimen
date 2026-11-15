package com.exaclast.regimen.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.exaclast.regimen.model.MessageException
import com.exaclast.regimen.sync.AutoBackupRetention
import com.exaclast.regimen.sync.BackupFiles
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates automatic encrypted backups to a user-chosen document tree:
 * - persisting SAF folder permissions,
 * - scheduling periodic and debounced WorkManager runs,
 * - writing `.regimen` files via [BackupManager] and pruning old backups per [AutoBackupRetention].
 */
class AutoBackupManager private constructor(context: Context) {

  enum class RunResult {
    Success,
    Skipped,
    RetryableFailure,
    PermanentFailure,
  }

  private val appContext = context.applicationContext
  val prefs = AutoBackupPreferences(appContext)
  val status: StateFlow<AutoBackupStatus> = prefs.status

  fun enableWithFolder(uri: Uri) {
    takeTreePermission(uri)
    val label = resolveFolderLabel(uri)
    val updated = prefs.update {
      it.copy(
        enabled = true,
        treeUri = uri.toString(),
        folderLabel = label,
        lastError = AutoBackupError.NONE,
      )
    }
    schedulePeriodic(updated.frequency, ExistingPeriodicWorkPolicy.UPDATE)
  }

  fun setFolder(uri: Uri) {
    takeTreePermission(uri)
    val label = resolveFolderLabel(uri)
    val updated = prefs.update {
      it.copy(
        treeUri = uri.toString(),
        folderLabel = label,
        lastError = AutoBackupError.NONE,
      )
    }
    if (updated.enabled) {
      schedulePeriodic(updated.frequency, ExistingPeriodicWorkPolicy.UPDATE)
    }
  }

  fun setEnabled(enabled: Boolean) {
    val updated = prefs.update {
      it.copy(
        enabled = enabled,
        lastError = if (enabled) AutoBackupError.NONE else it.lastError,
      )
    }
    if (enabled && updated.treeUri != null) {
      schedulePeriodic(updated.frequency, ExistingPeriodicWorkPolicy.KEEP)
    } else {
      cancelScheduledWork()
    }
  }

  fun setFrequency(frequency: AutoBackupFrequency) {
    val updated = prefs.update { it.copy(frequency = frequency) }
    if (updated.enabled && updated.treeUri != null) {
      schedulePeriodic(frequency, ExistingPeriodicWorkPolicy.UPDATE)
    }
  }

  fun setKeepCount(keepCount: Int) {
    prefs.update { it.copy(keepCount = keepCount.coerceAtLeast(1)) }
  }

  /** Re-arms periodic backups after the user sets up or unlocks the backup encryption key. */
  fun onEncryptionReady() {
    val current = prefs.current()
    if (!current.enabled || current.treeUri == null) return
    if (current.lastError == AutoBackupError.KEY_LOST || current.lastError == AutoBackupError.NOT_CONFIGURED) {
      val updated = prefs.update { it.copy(lastError = AutoBackupError.NONE) }
      schedulePeriodic(updated.frequency, ExistingPeriodicWorkPolicy.KEEP)
      enqueueDebouncedIfDue(updated)
    }
  }

  /**
   * Called after meaningful local data changes; schedules a debounced one-off backup if automatic
   * backups are enabled and the last successful backup is older than the configured period.
   */
  fun onDataChanged() {
    val current = prefs.current()
    if (!current.enabled || current.treeUri == null || current.lastError != AutoBackupError.NONE) return
    enqueueDebouncedIfDue(current)
  }

  /** Executes a scheduled backup run (skips cleanly if turned off). */
  fun runScheduledBackup(): RunResult {
    val config = prefs.current()
    if (!config.enabled) return RunResult.Skipped
    return executeBackup(config)
  }

  /** Executes an immediate backup (called from Settings -> Backup -> Back up now). */
  fun runBackupNow(): RunResult = executeBackup(prefs.current())

  @Synchronized
  private fun executeBackup(config: AutoBackupStatus): RunResult {
    val treeUriStr = config.treeUri
    if (treeUriStr.isNullOrBlank()) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.NOT_CONFIGURED) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }

    val backup = BackupManager.getInstance(appContext)
    if (backup.isEncryptionKeyLost) {
      prefs.update { it.copy(lastError = AutoBackupError.KEY_LOST) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }
    if (!backup.isEncryptionConfigured) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.NOT_CONFIGURED) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }

    val uri = runCatching { Uri.parse(treeUriStr) }.getOrNull()
    if (uri == null) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.FOLDER_MISSING) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }

    val hasGrant = appContext.contentResolver.persistedUriPermissions.any {
      it.uri == uri && it.isReadPermission && it.isWritePermission
    }
    if (!hasGrant) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.PERMISSION_LOST) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }

    val folder = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
    if (folder == null || !folder.exists() || !folder.isDirectory) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.FOLDER_MISSING) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }
    if (!folder.canWrite()) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.PERMISSION_LOST) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    }

    val existingDocs = folder.listFiles().filter { it.isFile && !it.name.isNullOrBlank() }
    val existingNames = existingDocs.mapNotNullTo(HashSet()) { it.name }
    val fileName = AutoBackupRetention.nextBackupFileName(existingNames)

    val createdDoc = try {
      folder.createFile(BackupFiles.MIME_TYPE, fileName)
        ?: throw IOException("Could not create backup file in selected folder")
    } catch (_: SecurityException) {
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.PERMISSION_LOST) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    } catch (_: Exception) {
      prefs.update { it.copy(lastError = AutoBackupError.WRITE_FAILED) }
      return RunResult.RetryableFailure
    }

    try {
      val out = appContext.contentResolver.openOutputStream(createdDoc.uri, "wt")
        ?: throw IOException("Could not open output stream")
      backup.exportEncrypted(out)
    } catch (_: MessageException) {
      runCatching { createdDoc.delete() }
      val error = if (backup.isEncryptionKeyLost) AutoBackupError.KEY_LOST else AutoBackupError.WRITE_FAILED
      prefs.update { it.copy(lastError = error) }
      if (error == AutoBackupError.KEY_LOST) cancelScheduledWork()
      return RunResult.PermanentFailure
    } catch (_: SecurityException) {
      runCatching { createdDoc.delete() }
      prefs.update { it.copy(enabled = false, lastError = AutoBackupError.PERMISSION_LOST) }
      cancelScheduledWork()
      return RunResult.PermanentFailure
    } catch (_: Exception) {
      runCatching { createdDoc.delete() }
      prefs.update { it.copy(lastError = AutoBackupError.WRITE_FAILED) }
      return RunResult.RetryableFailure
    }

    // Prune older managed files according to the retention policy.
    val docById = linkedMapOf<String, DocumentFile>()
    existingDocs.forEach { docById[it.uri.toString()] = it }
    docById[createdDoc.uri.toString()] = createdDoc

    val entries = docById.values.mapNotNull { doc ->
      val docName = doc.name ?: return@mapNotNull null
      AutoBackupRetention.BackupFileEntry(
        id = doc.uri.toString(),
        name = docName,
        lastModifiedMillis = doc.lastModified(),
      )
    }
    val plan = AutoBackupRetention.planRetention(
      files = entries,
      keepCount = config.keepCount,
    )
    var failedDeletes = 0
    for (entry in plan.toDelete) {
      val deleted = runCatching { docById[entry.id]?.delete() == true }.getOrDefault(false)
      if (!deleted) failedDeletes++
    }

    val keptCount = plan.kept.size + failedDeletes
    prefs.update {
      it.copy(
        folderLabel = folder.name ?: it.folderLabel,
        lastSuccessEpochMillis = System.currentTimeMillis(),
        lastKeptCount = keptCount,
        lastError = AutoBackupError.NONE,
      )
    }
    return RunResult.Success
  }

  private fun takeTreePermission(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { appContext.contentResolver.takePersistableUriPermission(uri, flags) }
  }

  private fun resolveFolderLabel(uri: Uri): String? =
    runCatching { DocumentFile.fromTreeUri(appContext, uri)?.name }.getOrNull()
      ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')

  private fun schedulePeriodic(
    frequency: AutoBackupFrequency,
    policy: ExistingPeriodicWorkPolicy,
  ) {
    val wm = workManagerOrNull() ?: return
    val constraints = Constraints.Builder()
      .setRequiresBatteryNotLow(true)
      .build()
    val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(frequency.days, TimeUnit.DAYS)
      .setConstraints(constraints)
      .build()
    wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC_WORK, policy, request)
  }

  private fun enqueueDebouncedIfDue(status: AutoBackupStatus) {
    val now = System.currentTimeMillis()
    val lastSuccess = status.lastSuccessEpochMillis
    if (lastSuccess != null && now - lastSuccess < status.frequency.periodMillis) return
    val wm = workManagerOrNull() ?: return
    val constraints = Constraints.Builder()
      .setRequiresBatteryNotLow(true)
      .build()
    val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
      .setInitialDelay(DEBOUNCE_DELAY_MINUTES, TimeUnit.MINUTES)
      .setConstraints(constraints)
      .build()
    wm.enqueueUniqueWork(UNIQUE_DEBOUNCED_WORK, ExistingWorkPolicy.REPLACE, request)
  }

  private fun cancelScheduledWork() {
    val wm = workManagerOrNull() ?: return
    wm.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
    wm.cancelUniqueWork(UNIQUE_DEBOUNCED_WORK)
  }

  private fun workManagerOrNull(): WorkManager? =
    runCatching { WorkManager.getInstance(appContext) }.getOrNull()

  companion object {
    private const val UNIQUE_PERIODIC_WORK = "regimen_auto_backup_periodic"
    private const val UNIQUE_DEBOUNCED_WORK = "regimen_auto_backup_debounced"
    private const val DEBOUNCE_DELAY_MINUTES = 2L

    @Volatile
    private var INSTANCE: AutoBackupManager? = null

    fun getInstance(context: Context): AutoBackupManager = INSTANCE ?: synchronized(this) {
      INSTANCE ?: AutoBackupManager(context.applicationContext).also { INSTANCE = it }
    }

    /** Fast entrypoint for repository mutations without instantiating WorkManager unless enabled. */
    fun notifyDataChanged(context: Context) {
      getInstance(context).onDataChanged()
    }

    @VisibleForTesting
    internal fun resetForTests() {
      synchronized(this) {
        INSTANCE = null
      }
    }
  }
}
