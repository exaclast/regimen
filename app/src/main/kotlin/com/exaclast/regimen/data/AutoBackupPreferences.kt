package com.exaclast.regimen.data

import android.content.Context
import com.exaclast.regimen.sync.AutoBackupRetention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoBackupFrequency(val days: Long) {
  DAILY(1L),
  WEEKLY(7L);

  val periodMillis: Long
    get() = days * 24L * 60L * 60L * 1000L
}

enum class AutoBackupError {
  NONE,
  PERMISSION_LOST,
  FOLDER_MISSING,
  KEY_LOST,
  NOT_CONFIGURED,
  WRITE_FAILED,
}

data class AutoBackupStatus(
  val enabled: Boolean = false,
  val treeUri: String? = null,
  val folderLabel: String? = null,
  val frequency: AutoBackupFrequency = AutoBackupFrequency.DAILY,
  val keepCount: Int = AutoBackupRetention.DEFAULT_KEEP_COUNT,
  val lastSuccessEpochMillis: Long? = null,
  val lastKeptCount: Int? = null,
  val lastError: AutoBackupError = AutoBackupError.NONE,
)

/**
 * Device-local automatic backup settings and last-run status.
 * Stored in its own preferences file and excluded from Android Auto Backup so a restored phone
 * never inherits another device's document tree URI.
 */
class AutoBackupPreferences(context: Context) {

  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val _status = MutableStateFlow(readStatus())
  val status: StateFlow<AutoBackupStatus> = _status.asStateFlow()

  @Synchronized
  fun current(): AutoBackupStatus = _status.value

  @Synchronized
  fun update(transform: (AutoBackupStatus) -> AutoBackupStatus): AutoBackupStatus {
    val next = transform(readStatus())
    writeStatus(next)
    _status.value = next
    return next
  }

  private fun readStatus(): AutoBackupStatus {
    val freqName = prefs.getString(KEY_FREQUENCY, AutoBackupFrequency.DAILY.name)
    val frequency = AutoBackupFrequency.entries.find { it.name == freqName } ?: AutoBackupFrequency.DAILY
    val errName = prefs.getString(KEY_LAST_ERROR, AutoBackupError.NONE.name)
    val error = AutoBackupError.entries.find { it.name == errName } ?: AutoBackupError.NONE
    val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, -1L).takeIf { it > 0L }
    val lastKept = prefs.getInt(KEY_LAST_KEPT, -1).takeIf { it >= 0 }
    return AutoBackupStatus(
      enabled = prefs.getBoolean(KEY_ENABLED, false),
      treeUri = prefs.getString(KEY_TREE_URI, null),
      folderLabel = prefs.getString(KEY_FOLDER_LABEL, null),
      frequency = frequency,
      keepCount = prefs.getInt(KEY_KEEP_COUNT, AutoBackupRetention.DEFAULT_KEEP_COUNT)
        .coerceAtLeast(1),
      lastSuccessEpochMillis = lastSuccess,
      lastKeptCount = lastKept,
      lastError = error,
    )
  }

  private fun writeStatus(status: AutoBackupStatus) {
    prefs.edit().apply {
      putBoolean(KEY_ENABLED, status.enabled)
      if (status.treeUri != null) putString(KEY_TREE_URI, status.treeUri) else remove(KEY_TREE_URI)
      if (status.folderLabel != null) putString(KEY_FOLDER_LABEL, status.folderLabel) else remove(KEY_FOLDER_LABEL)
      putString(KEY_FREQUENCY, status.frequency.name)
      putInt(KEY_KEEP_COUNT, status.keepCount.coerceAtLeast(1))
      if (status.lastSuccessEpochMillis != null) putLong(KEY_LAST_SUCCESS, status.lastSuccessEpochMillis) else remove(KEY_LAST_SUCCESS)
      if (status.lastKeptCount != null) putInt(KEY_LAST_KEPT, status.lastKeptCount) else remove(KEY_LAST_KEPT)
      putString(KEY_LAST_ERROR, status.lastError.name)
    }.commit()
  }

  companion object {
    const val PREFS_NAME = "regimen_auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_FOLDER_LABEL = "folder_label"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_KEEP_COUNT = "keep_count"
    private const val KEY_LAST_SUCCESS = "last_success_epoch_ms"
    private const val KEY_LAST_KEPT = "last_kept_count"
    private const val KEY_LAST_ERROR = "last_error"
  }
}
