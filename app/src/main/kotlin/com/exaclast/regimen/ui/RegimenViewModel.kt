package com.exaclast.regimen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.exaclast.regimen.data.BackupManager
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DailyDoseBoard
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.components.UiFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class RegimenUiState(
  val selectedDateIso: String,
  val todayIso: String,
  val nowMillis: Long,
  val snapshot: RegimenSnapshot,
  val dailyBoard: DailyDoseBoard,
  /** Pending scheduled doses for *today*, regardless of which date is being viewed. */
  val todayPendingCount: Int,
) {
  val isToday: Boolean
    get() = selectedDateIso == todayIso

  val isFuture: Boolean
    get() = selectedDateIso > todayIso
}

/** An action that reverses a previous change. */
sealed interface UndoAction {
  /** Each entry: the log state before the change (null = there was none) and the id of the log that was written. */
  data class RestoreLogs(val entries: List<Pair<DoseLog?, String>>) : UndoAction

  data class RestoreDeletedLog(val log: DoseLog) : UndoAction

  data class RestoreSchedule(val schedule: MedicationSchedule) : UndoAction

  /** Puts back everything that was on the device before a backup restore. */
  data class RestoreBackup(val previous: RegimenBackupBundle) : UndoAction
}

/** The restore flow: pick a file → (unlock) → preview → merge or replace. */
sealed interface RestoreUiState {
  data object Idle : RestoreUiState
  data object Opening : RestoreUiState
  data class Locked(
    val locked: BackupManager.OpenResult.NeedsUnlock,
    val error: String? = null,
    val busy: Boolean = false,
  ) : RestoreUiState
  data class Preview(val ready: BackupManager.OpenResult.Ready, val busy: Boolean = false) : RestoreUiState
  /** Restored, or failed to open: the restore screen closes. */
  data object Finished : RestoreUiState
}

data class UiMessage(
  val text: String,
  val undo: UndoAction? = null,
  val id: Long = System.nanoTime(),
)

class RegimenViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = RegimenRepository.getInstance(application)

  /** Null = follow the current day (rolls over at midnight). */
  private val selectedDateOverride = MutableStateFlow<String?>(null)

  private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
  val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

  /** Ticks at each minute boundary so window phases, pacing, and "today" stay current. */
  private val clock: Flow<Long> = flow {
    while (true) {
      val now = System.currentTimeMillis()
      emit(now)
      delay(60_000L - now % 60_000L)
    }
  }

  val uiState: StateFlow<RegimenUiState> = combine(
    repository.snapshot,
    selectedDateOverride,
    clock,
  ) { snapshot, override, now ->
    buildUiState(snapshot, override, now)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = buildUiState(repository.snapshot.value, null, System.currentTimeMillis()),
  )

  init {
    val s = repository.snapshot.value
    DoseNotificationManager.ensureChannels(application)
    DoseNotificationManager.refresh(application, s)
  }

  private fun buildUiState(snapshot: RegimenSnapshot, override: String?, now: Long): RegimenUiState {
    val todayIso = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val dateIso = override ?: todayIso
    val board = boardFor(snapshot, dateIso, now)
    val todayBoard = if (dateIso == todayIso) board else boardFor(snapshot, todayIso, now)
    return RegimenUiState(
      selectedDateIso = dateIso,
      todayIso = todayIso,
      nowMillis = now,
      snapshot = snapshot,
      dailyBoard = board,
      todayPendingCount = todayBoard.bucketGroups.sumOf { it.pendingCount },
    )
  }

  private fun boardFor(snapshot: RegimenSnapshot, dateIso: String, now: Long): DailyDoseBoard =
    DoseEngine.buildDailyBoard(
      dateIso = dateIso,
      medications = snapshot.medications,
      schedules = snapshot.schedules,
      buckets = snapshot.buckets,
      logs = snapshot.doseLogs,
      conditions = snapshot.conditions,
      providers = snapshot.providers,
      nowMillis = now,
    )

  // region Date navigation

  fun selectDate(dateIso: String) {
    selectedDateOverride.value = dateIso.takeIf { it != uiState.value.todayIso }
  }

  fun goToToday() {
    selectedDateOverride.value = null
  }

  fun shiftDateByDays(days: Long) {
    val current = runCatching { LocalDate.parse(uiState.value.selectedDateIso) }.getOrDefault(LocalDate.now())
    selectDate(current.plusDays(days).toString())
  }

  // endregion

  // region Dose logging (each emits a message with Undo)

  /** Logs a scheduled dose as taken, now or at [takenAtMillis] (e.g. the window end for "taken on time"). */
  fun takeScheduledDose(item: ScheduledDoseItem, site: String? = item.suggestedSite, takenAtMillis: Long? = null) {
    val log = writeScheduledLog(item, DoseStatus.TAKEN, site, takenAtMillis = takenAtMillis) ?: return
    val siteNote = log.siteUsed?.let { " · $it" } ?: ""
    val timeNote = takenAtMillis?.let { " as taken at ${UiFormat.time(it)}" } ?: ""
    post(
      "Logged ${item.medication.primaryName}$timeNote$siteNote",
      UndoAction.RestoreLogs(listOf(item.existingLog to log.id)),
    )
  }

  fun skipScheduledDose(item: ScheduledDoseItem) {
    val log = writeScheduledLog(item, DoseStatus.SKIPPED, site = null) ?: return
    post("Skipped ${item.medication.primaryName}", UndoAction.RestoreLogs(listOf(item.existingLog to log.id)))
  }

  fun snoozeScheduledDose(
    item: ScheduledDoseItem,
    minutes: Int = repository.snapshot.value.settings.snoozeMinutes,
  ) {
    val until = System.currentTimeMillis() + minutes * 60_000L
    val log = writeScheduledLog(item, DoseStatus.SNOOZED, item.suggestedSite, snoozedUntil = until) ?: return
    val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalTime()
    post(
      "Snoozed until ${TimeOfDayBucket.formatClockTime(time.hour, time.minute)}",
      UndoAction.RestoreLogs(listOf(item.existingLog to log.id)),
    )
  }

  /** Clears a taken/skipped/snoozed state so the dose is pending again. */
  fun clearScheduledDoseLog(item: ScheduledDoseItem) {
    val existing = item.existingLog ?: return
    repository.deleteDoseLog(existing.id)
    post("Marked ${item.medication.primaryName} as not taken", UndoAction.RestoreDeletedLog(existing))
  }

  fun takeAllPending(items: List<ScheduledDoseItem>) {
    val entries = items.filter { !it.isCompleted }.mapNotNull { item ->
      writeScheduledLog(item, DoseStatus.TAKEN, item.suggestedSite)?.let { item.existingLog to it.id }
    }
    if (entries.isEmpty()) return
    val noun = if (entries.size == 1) "dose" else "doses"
    post("Logged ${entries.size} $noun", UndoAction.RestoreLogs(entries))
  }

  fun logPrnOrCustomDose(
    medication: Medication,
    amount: Double = medication.defaultDoseAmount,
    unit: DoseUnit = medication.defaultDoseUnit,
    siteUsed: String? = null,
    dateIso: String = uiState.value.todayIso,
  ) {
    val log = repository.logDose(
      medicationId = medication.id,
      scheduleId = null,
      bucketId = null,
      scheduledDateIso = dateIso,
      status = DoseStatus.TAKEN,
      amountTaken = amount,
      amountUnit = unit,
      siteUsed = siteUsed,
    ) ?: return
    val siteNote = log.siteUsed?.let { " · $it" } ?: ""
    post(
      "Logged ${medication.primaryName} ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}$siteNote",
      UndoAction.RestoreLogs(listOf(null to log.id)),
    )
  }

  fun deleteDoseLog(log: DoseLog) {
    repository.deleteDoseLog(log.id)
    post("Log entry deleted", UndoAction.RestoreDeletedLog(log))
  }

  fun undo(action: UndoAction) {
    when (action) {
      is UndoAction.RestoreLogs -> action.entries.forEach { (previous, writtenId) ->
        if (previous != null) repository.restoreDoseLog(previous) else repository.deleteDoseLog(writtenId)
      }
      is UndoAction.RestoreDeletedLog -> repository.restoreDoseLog(action.log)
      is UndoAction.RestoreSchedule -> repository.restoreSchedule(action.schedule)
      is UndoAction.RestoreBackup -> viewModelScope.launch {
        withContext(Dispatchers.IO) { backup.undoRestore(action.previous) }
        post("Restore undone")
      }
    }
  }

  private fun writeScheduledLog(
    item: ScheduledDoseItem,
    status: DoseStatus,
    site: String?,
    snoozedUntil: Long? = null,
    takenAtMillis: Long? = null,
  ): DoseLog? = repository.logDose(
    medicationId = item.medication.id,
    scheduleId = item.schedule.id,
    bucketId = item.bucket.id,
    scheduledDateIso = item.dateIso,
    status = status,
    amountTaken = item.schedule.doseAmount,
    amountUnit = item.schedule.doseUnit,
    siteUsed = site,
    snoozedUntilMillis = snoozedUntil,
    existingLogId = item.existingLog?.id,
    actionTimestampMillis = takenAtMillis,
  )

  private fun post(text: String, undo: UndoAction? = null) {
    _messages.tryEmit(UiMessage(text, undo))
  }

  // endregion

  // region Records

  fun saveMedication(medication: Medication) = repository.saveMedication(medication)

  fun deleteMedication(medicationId: String) = repository.deleteMedication(medicationId)

  fun saveSchedule(schedule: MedicationSchedule) = repository.saveSchedule(schedule)

  fun deleteSchedule(schedule: MedicationSchedule) {
    repository.deleteSchedule(schedule.id)
    post("Schedule deleted", UndoAction.RestoreSchedule(schedule))
  }

  fun saveProvider(provider: Provider) = repository.saveProvider(provider)

  fun deleteProvider(prescriberId: String) = repository.deleteProvider(prescriberId)

  fun setProviderConditions(providerId: String, conditionIds: Set<String>) =
    repository.setProviderConditions(providerId, conditionIds)

  fun saveCondition(condition: Condition) = repository.saveCondition(condition)

  fun deleteCondition(conditionId: String) = repository.deleteCondition(conditionId)

  fun saveBucket(bucket: TimeOfDayBucket) = repository.saveBucket(bucket)

  fun deleteBucket(bucketId: String) = repository.deleteBucket(bucketId)

  fun resetBucketsToDefault() = repository.resetBucketsToDefault()

  fun saveSettings(settings: AppSettings) = repository.saveSettings(settings)

  fun refreshPermissionsAndAlarms() {
    val s = repository.snapshot.value
    DoseNotificationManager.refresh(getApplication(), s)
  }

  fun clearAllUserData() = repository.clearAllUserData()

  fun resetToSampleData() = repository.resetToSampleData()

  /** Previews the real notification for [item]'s window (all of its doses still due). */
  fun sendTestReminder(item: ScheduledDoseItem, stage: DoseNotificationStage): Boolean {
    val snapshot = repository.snapshot.value
    val items = DoseNotificationManager.actionableItems(snapshot, item.bucket.id, item.dateIso)
      .ifEmpty { listOf(item) }
    return DoseNotificationManager.postWindowNotification(
      context = getApplication(),
      bucket = item.bucket,
      dateIso = item.dateIso,
      items = items,
      stage = stage,
      settings = snapshot.settings,
    )
  }

  // endregion

  // region Backup

  private val backup = BackupManager.getInstance(application)

  private val _backupEncryptionConfigured = MutableStateFlow(backup.isEncryptionConfigured)
  val backupEncryptionConfigured: StateFlow<Boolean> = _backupEncryptionConfigured

  /** Set when Export was tapped before a passphrase existed: export once setup finishes. */
  private val _pendingExport = MutableStateFlow(false)
  val pendingExport: StateFlow<Boolean> = _pendingExport

  private val _restoreState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
  val restoreState: StateFlow<RestoreUiState> = _restoreState

  fun suggestedBackupFileName(encrypted: Boolean): String = backup.suggestedFileName(encrypted)

  fun requestExportAfterSetup() {
    _pendingExport.value = true
  }

  fun consumePendingExport() {
    _pendingExport.value = false
  }

  /** Runs a passphrase operation (slow key derivation) off the main thread. */
  private fun passphraseTask(block: () -> String?, onResult: (Result<String?>) -> Unit) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.Default) { runCatching(block) }
      _backupEncryptionConfigured.value = backup.isEncryptionConfigured
      onResult(result)
    }
  }

  /** Result: the recovery code. */
  fun setUpBackupEncryption(passphrase: String, reset: Boolean, onResult: (Result<String?>) -> Unit) =
    passphraseTask({
      if (reset) backup.resetEncryption(passphrase.toCharArray()) else backup.setUpEncryption(passphrase.toCharArray())
    }, onResult)

  fun changeBackupPassphrase(current: String, new: String, onResult: (Result<String?>) -> Unit) =
    passphraseTask({ backup.changePassphrase(current.toCharArray(), new.toCharArray()); null }, onResult)

  fun revealRecoveryCode(passphrase: String, onResult: (Result<String?>) -> Unit) =
    passphraseTask({ backup.recoveryCode(passphrase.toCharArray()) }, onResult)

  fun exportBackup(uri: Uri, encrypted: Boolean) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { if (encrypted) backup.exportEncrypted(uri) else backup.exportPlainJson(uri) }
      }
      post(
        result.fold(
          onSuccess = { if (encrypted) "Encrypted backup saved" else "Unencrypted backup saved" },
          onFailure = { "Couldn't save the backup: ${it.message}" },
        ),
      )
    }
  }

  fun openBackup(uri: Uri) {
    _restoreState.value = RestoreUiState.Opening
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { backup.open(uri) } }
      _restoreState.value = result.fold(
        onSuccess = {
          when (it) {
            is BackupManager.OpenResult.Ready -> RestoreUiState.Preview(it)
            is BackupManager.OpenResult.NeedsUnlock -> RestoreUiState.Locked(it)
          }
        },
        onFailure = {
          post("Couldn't open the backup: ${it.message}")
          RestoreUiState.Finished
        },
      )
    }
  }

  fun unlockBackup(secret: String, isRecoveryCode: Boolean) {
    val locked = _restoreState.value as? RestoreUiState.Locked ?: return
    _restoreState.value = locked.copy(busy = true, error = null)
    viewModelScope.launch {
      val result = withContext(Dispatchers.Default) {
        runCatching { backup.unlock(locked.locked, secret, isRecoveryCode) }
      }
      _backupEncryptionConfigured.value = backup.isEncryptionConfigured
      _restoreState.value = result.fold(
        onSuccess = { RestoreUiState.Preview(it) },
        onFailure = { locked.copy(busy = false, error = it.message ?: "Couldn't unlock the backup") },
      )
    }
  }

  fun applyRestore(replace: Boolean) {
    val preview = _restoreState.value as? RestoreUiState.Preview ?: return
    _restoreState.value = preview.copy(busy = true)
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { backup.restore(preview.ready.bundle, replace) } }
      result.fold(
        onSuccess = { outcome ->
          val conflictNote = when (val n = outcome.conflicts.size) {
            0 -> ""
            1 -> " · 1 conflict resolved"
            else -> " · $n conflicts resolved"
          }
          post(
            (if (replace) "Replaced with backup" else "Backup merged") + conflictNote,
            UndoAction.RestoreBackup(outcome.previous),
          )
          _restoreState.value = RestoreUiState.Finished
        },
        onFailure = {
          post("Couldn't restore: ${it.message}")
          _restoreState.value = preview.copy(busy = false)
        },
      )
    }
  }

  fun resetRestore() {
    _restoreState.value = RestoreUiState.Idle
  }

  // endregion
}
