package com.exaclast.regimen.ui

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.exaclast.regimen.data.AutoBackupFrequency
import com.exaclast.regimen.data.AutoBackupManager
import com.exaclast.regimen.data.AutoBackupStatus
import com.exaclast.regimen.data.BackupManager
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.domain.DailyDoseBoard
import com.exaclast.regimen.domain.BucketDoseGroup
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.DoseTimeline
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.R
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.model.userMessage
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class RegimenUiState(
  val selectedDateIso: String,
  val todayIso: String,
  val nowMillis: Long,
  val snapshot: RegimenSnapshot,
  val dailyBoard: DailyDoseBoard,
  /** Pending scheduled doses for *today*, regardless of which date is being viewed. */
  val todayPendingCount: Int,
  val todayBoard: DailyDoseBoard,
  /** Last night's overnight windows that are still open, shown on Today. */
  val carriedOver: List<BucketDoseGroup>,
  val yesterdayIso: String,
  val yesterdayMissedCount: Int,
  /** Medications whose schedule can't produce doses because its windows no longer exist. */
  val medicationsWithoutWindow: List<Medication>,
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

  /** Puts a medication back as it was, e.g. after deleting one of its changes. */
  data class RestoreMedication(val medication: Medication) : UndoAction

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
  data class SetNewPassphrase(
    val dataKey: ByteArray,
    val busy: Boolean = false,
    val error: String? = null,
  ) : RestoreUiState {
    override fun equals(other: Any?): Boolean =
      this === other || (other is SetNewPassphrase && dataKey.contentEquals(other.dataKey) && busy == other.busy && error == other.error)

    override fun hashCode(): Int {
      var result = dataKey.contentHashCode()
      result = 31 * result + busy.hashCode()
      result = 31 * result + (error?.hashCode() ?: 0)
      return result
    }
  }
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
  }.flowOn(Dispatchers.Default).stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = buildUiState(repository.snapshot.value, null, System.currentTimeMillis()),
  )

  init {
    viewModelScope.launch(Dispatchers.IO) {
      DoseNotificationManager.ensureChannels(getApplication())
      DoseNotificationManager.refresh(getApplication(), repository.snapshot.value)
      refreshBackupKeyStatus()
    }
  }

  private fun buildUiState(snapshot: RegimenSnapshot, override: String?, now: Long): RegimenUiState {
    val todayIso = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val dateIso = override ?: todayIso
    val board = boardFor(snapshot, dateIso, now)
    val todayBoard = if (dateIso == todayIso) board else boardFor(snapshot, todayIso, now)
    val today = LocalDate.parse(todayIso)
    val yesterdayIso = today.minusDays(1).toString()
    val yesterdayBoard = if (dateIso == yesterdayIso) board else boardFor(snapshot, yesterdayIso, now)
    val carriedOver = DoseTimeline.stillOpenFromYesterday(yesterdayBoard, now)
    return RegimenUiState(
      selectedDateIso = dateIso,
      todayIso = todayIso,
      nowMillis = now,
      snapshot = snapshot,
      dailyBoard = board,
      todayPendingCount = (carriedOver + todayBoard.bucketGroups).sumOf { it.pendingCount },
      todayBoard = todayBoard,
      carriedOver = carriedOver,
      yesterdayIso = yesterdayIso,
      yesterdayMissedCount = DoseTimeline.missedCount(yesterdayBoard, now),
      medicationsWithoutWindow = DoseEngine.medicationsWithoutWindow(
        snapshot.medications, snapshot.schedules, snapshot.buckets, today,
      ),
    )
  }

  private fun boardFor(snapshot: RegimenSnapshot, dateIso: String, now: Long): DailyDoseBoard =
    DoseEngine.buildDailyBoard(
      dateIso = dateIso,
      medications = snapshot.medications,
      schedules = snapshot.schedules,
      buckets = snapshot.buckets,
      logs = snapshot.doseLogs,
      indications = snapshot.indications,
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
  fun takeScheduledDose(
    item: ScheduledDoseItem,
    site: String? = item.suggestedSite,
    takenAtMillis: Long? = null,
    note: String? = null,
  ) {
    viewModelScope.launch {
      val log = withContext(Dispatchers.IO) {
        writeScheduledLog(item, DoseStatus.TAKEN, site, takenAtMillis = takenAtMillis, notes = note)
      } ?: return@launch
      val name = item.medication.primaryName
      val logged = takenAtMillis?.let { str(R.string.vm_logged_taken_at, name, UiFormat.time(it)) } ?: str(R.string.vm_logged, name)
      post(
        listOfNotNull(logged, log.siteUsed).joinToString(" · "),
        UndoAction.RestoreLogs(listOf(item.existingLog to log.id)),
      )
    }
  }

  fun skipScheduledDose(item: ScheduledDoseItem) {
    viewModelScope.launch {
      val log = withContext(Dispatchers.IO) {
        writeScheduledLog(item, DoseStatus.SKIPPED, site = null)
      } ?: return@launch
      post(str(R.string.vm_skipped, item.medication.primaryName), UndoAction.RestoreLogs(listOf(item.existingLog to log.id)))
    }
  }

  fun snoozeScheduledDose(
    item: ScheduledDoseItem,
    minutes: Int = repository.snapshot.value.settings.snoozeMinutes,
  ) {
    val until = System.currentTimeMillis() + minutes * 60_000L
    viewModelScope.launch {
      val log = withContext(Dispatchers.IO) {
        writeScheduledLog(item, DoseStatus.SNOOZED, item.suggestedSite, snoozedUntil = until)
      } ?: return@launch
      val time = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).toLocalTime()
      post(
        str(R.string.vm_snoozed_until, TimeOfDayBucket.formatClockTime(time.hour, time.minute)),
        UndoAction.RestoreLogs(listOf(item.existingLog to log.id)),
      )
    }
  }

  /** Clears a taken/skipped/snoozed state so the dose is pending again. */
  fun clearScheduledDoseLog(item: ScheduledDoseItem) {
    val existing = item.existingLog ?: return
    viewModelScope.launch {
      withContext(Dispatchers.IO) { repository.deleteDoseLog(existing.id) }
      post(str(R.string.vm_marked_not_taken, item.medication.primaryName), UndoAction.RestoreDeletedLog(existing))
    }
  }

  fun takeAllPending(items: List<ScheduledDoseItem>) {
    val pending = items.filter { !it.isCompleted }
    if (pending.isEmpty()) return
    viewModelScope.launch {
      val logs = withContext(Dispatchers.IO) {
        repository.logDoses(
          pending.map { item ->
            RegimenRepository.DoseLogRequest(
              medicationId = item.medication.id,
              scheduleId = item.schedule.id,
              bucketId = item.bucket.id,
              scheduledDateIso = item.dateIso,
              status = DoseStatus.TAKEN,
              amountTaken = item.schedule.doseAmount,
              amountUnit = item.schedule.doseUnit,
              siteUsed = item.suggestedSite,
              existingLogId = item.existingLog?.id,
            )
          },
        )
      }
      if (logs.isEmpty()) return@launch
      val previousById = pending.associateBy({ it.existingLog?.id }, { it.existingLog })
      val entries = logs.map { written -> previousById[written.id] to written.id }
      post(plural(R.plurals.vm_logged_doses, entries.size, entries.size), UndoAction.RestoreLogs(entries))
    }
  }

  /**
   * Logs an unscheduled dose on [dateIso] (the day being viewed), taken now. With [takenAtMillis] it's
   * logged at that time instead, on that time's own day.
   */
  fun logPrnOrCustomDose(
    medication: Medication,
    amount: Double = medication.defaultDoseAmount,
    unit: DoseUnit = medication.defaultDoseUnit,
    siteUsed: String? = null,
    dateIso: String = uiState.value.todayIso,
    takenAtMillis: Long? = null,
    note: String? = null,
  ) {
    // If taken now (takenAtMillis == null), log on today's date so current time isn't stamped on a past day.
    val day = takenAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
      ?: if (dateIso < uiState.value.todayIso) uiState.value.todayIso else dateIso
    viewModelScope.launch {
      val log = withContext(Dispatchers.IO) {
        repository.logDose(
          medicationId = medication.id,
          scheduleId = null,
          bucketId = null,
          scheduledDateIso = day,
          status = DoseStatus.TAKEN,
          amountTaken = amount,
          amountUnit = unit,
          siteUsed = siteUsed,
          notes = note,
          actionTimestampMillis = takenAtMillis,
        )
      } ?: return@launch
      val logged = str(R.string.vm_logged_dose, medication.primaryName, formatNum(log.amountTaken), log.amountUnit.symbol)
      val resources = getApplication<Application>().resources
      post(
        listOfNotNull(logged, takenAtMillis?.let { UiFormat.dateTime(resources, it) }, log.siteUsed).joinToString(" · "),
        UndoAction.RestoreLogs(listOf(null to log.id)),
      )
    }
  }

  fun deleteDoseLog(log: DoseLog) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { repository.deleteDoseLog(log.id) }
      post(str(R.string.vm_log_deleted), UndoAction.RestoreDeletedLog(log))
    }
  }

  /** Saves an edited log; Undo puts the previous version back. */
  fun saveDoseLog(updated: DoseLog) {
    val previous = repository.snapshot.value.doseLogs.find { it.id == updated.id } ?: return
    if (previous == updated) return
    viewModelScope.launch {
      withContext(Dispatchers.IO) { repository.updateDoseLog(updated) }
      post(str(R.string.vm_log_updated), UndoAction.RestoreLogs(listOf(previous to updated.id)))
    }
  }

  fun undo(action: UndoAction) {
    when (action) {
      is UndoAction.RestoreLogs -> viewModelScope.launch {
        withContext(Dispatchers.IO) {
          val toRestore = action.entries.mapNotNull { it.first }
          val toDeleteIds = action.entries.filter { it.first == null }.map { it.second }
          repository.restoreAndDeleteDoseLogs(toRestore, toDeleteIds)
        }
      }
      is UndoAction.RestoreDeletedLog -> viewModelScope.launch {
        withContext(Dispatchers.IO) { repository.restoreDoseLog(action.log) }
      }
      is UndoAction.RestoreSchedule -> viewModelScope.launch {
        withContext(Dispatchers.IO) { repository.restoreSchedule(action.schedule) }
      }
      is UndoAction.RestoreMedication -> viewModelScope.launch {
        withContext(Dispatchers.IO) { repository.saveMedication(action.medication) }
      }
      is UndoAction.RestoreBackup -> viewModelScope.launch {
        withContext(Dispatchers.IO) { backup.undoRestore(action.previous) }
        post(str(R.string.vm_restore_undone))
      }
    }
  }

  private fun writeScheduledLog(
    item: ScheduledDoseItem,
    status: DoseStatus,
    site: String?,
    snoozedUntil: Long? = null,
    takenAtMillis: Long? = null,
    notes: String? = null,
  ): DoseLog? = repository.logDose(
    medicationId = item.medication.id,
    scheduleId = item.schedule.id,
    bucketId = item.bucket.id,
    scheduledDateIso = item.dateIso,
    status = status,
    amountTaken = item.schedule.doseAmount,
    amountUnit = item.schedule.doseUnit,
    siteUsed = site,
    notes = notes,
    snoozedUntilMillis = snoozedUntil,
    existingLogId = item.existingLog?.id,
    actionTimestampMillis = takenAtMillis,
  )

  private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

  private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    getApplication<Application>().resources.getQuantityString(id, count, *args)

  private fun errorText(error: Throwable): String? =
    error.userMessage(getApplication<Application>().resources.texts())

  private fun post(text: String, undo: UndoAction? = null) {
    _messages.tryEmit(UiMessage(text, undo))
  }

  // endregion

  // region Records

  fun saveMedication(medication: Medication) = repository.saveMedication(medication)

  fun deleteMedication(medicationId: String) = repository.deleteMedication(medicationId)

  /** Saves a planned or edited change; one on the same date as another merges into it. */
  fun saveAdjustment(medicationId: String, adjustment: MedicationAdjustment) {
    val medication = repository.snapshot.value.medications.find { it.id == medicationId } ?: return
    repository.saveMedication(Adjustments.upsert(medication, adjustment))
  }

  fun deleteAdjustment(medicationId: String, adjustmentId: String) {
    val medication = repository.snapshot.value.medications.find { it.id == medicationId } ?: return
    repository.saveMedication(Adjustments.remove(medication, adjustmentId))
    post(str(R.string.vm_change_deleted), UndoAction.RestoreMedication(medication))
  }

  fun saveSchedule(schedule: MedicationSchedule) = repository.saveSchedule(schedule)

  fun saveScheduleEdit(medication: Medication?, schedules: List<MedicationSchedule>) =
    repository.saveScheduleEdit(medication, schedules)

  fun deleteSchedule(schedule: MedicationSchedule) {
    repository.deleteSchedule(schedule.id)
    post(str(R.string.vm_schedule_deleted), UndoAction.RestoreSchedule(schedule))
  }

  fun saveProvider(provider: Provider) = repository.saveProvider(provider)

  fun deleteProvider(prescriberId: String) = repository.deleteProvider(prescriberId)

  fun setProviderIndications(providerId: String, indicationIds: Set<String>) =
    repository.setProviderIndications(providerId, indicationIds)

  fun setIndicationProviders(indicationId: String, providerIds: Set<String>) =
    repository.setIndicationProviders(indicationId, providerIds)

  fun setIndicationMedications(indicationId: String, medicationIds: Set<String>) =
    repository.setIndicationMedications(indicationId, medicationIds)

  fun saveIndication(indication: Indication) = repository.saveIndication(indication)

  fun deleteIndication(indicationId: String) = repository.deleteIndication(indicationId)

  fun saveBucket(bucket: TimeOfDayBucket) = repository.saveBucket(bucket)

  fun deleteBucket(bucketId: String, moveToBucketId: String?) = repository.deleteBucket(bucketId, moveToBucketId)

  fun resetBucketsToDefault() = repository.resetBucketsToDefault()

  fun saveSettings(settings: AppSettings) = repository.saveSettings(settings)

  fun refreshPermissionsAndAlarms() {
    val s = repository.snapshot.value
    DoseNotificationManager.refresh(getApplication(), s)
  }

  fun clearAllUserData() = repository.clearAllUserData()

  fun resetToSampleData() = repository.resetToSampleData()

  /** Sends a labelled test notification with no dose actions. */
  fun sendTestReminder(stage: DoseNotificationStage, targetLabel: String?): Boolean {
    val snapshot = repository.snapshot.value
    return DoseNotificationManager.postTestNotification(
      context = getApplication(),
      stage = stage,
      settings = snapshot.settings,
      targetLabel = targetLabel,
    )
  }

  // endregion

  // region Backup

  private val backup = BackupManager.getInstance(application)
  private val autoBackup = AutoBackupManager.getInstance(application)

  val autoBackupStatus: StateFlow<AutoBackupStatus> = autoBackup.status

  private val _autoBackupBusy = MutableStateFlow(false)
  val autoBackupBusy: StateFlow<Boolean> = _autoBackupBusy

  private val _pendingAutoBackupSetup = MutableStateFlow(false)
  val pendingAutoBackupSetup: StateFlow<Boolean> = _pendingAutoBackupSetup

  private val _backupEncryptionConfigured = MutableStateFlow(false)
  val backupEncryptionConfigured: StateFlow<Boolean> = _backupEncryptionConfigured

  private val _backupKeyLost = MutableStateFlow(false)
  val backupKeyLost: StateFlow<Boolean> = _backupKeyLost

  /** Set when Export was tapped before a passphrase existed: export once setup finishes. */
  private val _pendingExport = MutableStateFlow(false)
  val pendingExport: StateFlow<Boolean> = _pendingExport

  private val _restoreState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
  val restoreState: StateFlow<RestoreUiState> = _restoreState

  private fun refreshBackupKeyStatus() {
    val status = backup.keys.status()
    _backupEncryptionConfigured.value = status == com.exaclast.regimen.data.BackupKeyStore.KeyStatus.CONFIGURED
    _backupKeyLost.value = status == com.exaclast.regimen.data.BackupKeyStore.KeyStatus.KEY_LOST
    if (status == com.exaclast.regimen.data.BackupKeyStore.KeyStatus.CONFIGURED) {
      autoBackup.onEncryptionReady()
    }
  }

  fun suggestedBackupFileName(encrypted: Boolean): String = backup.suggestedFileName(encrypted)

  fun requestExportAfterSetup() {
    _pendingExport.value = true
  }

  fun consumePendingExport() {
    _pendingExport.value = false
  }

  fun requestAutoBackupAfterSetup() {
    _pendingAutoBackupSetup.value = true
  }

  fun consumePendingAutoBackupSetup() {
    _pendingAutoBackupSetup.value = false
  }

  fun enableAutoBackupWithFolder(uri: Uri) {
    viewModelScope.launch {
      _autoBackupBusy.value = true
      val result = withContext(Dispatchers.IO) {
        autoBackup.enableWithFolder(uri)
        autoBackup.runBackupNow()
      }
      _autoBackupBusy.value = false
      if (result == AutoBackupManager.RunResult.Success) {
        post(str(R.string.vm_auto_backup_saved))
      } else if (result != AutoBackupManager.RunResult.Skipped) {
        post(str(R.string.vm_auto_backup_failed))
      }
    }
  }

  fun setAutoBackupFolder(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      autoBackup.setFolder(uri)
    }
  }

  fun setAutoBackupEnabled(enabled: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      autoBackup.setEnabled(enabled)
    }
  }

  fun setAutoBackupFrequency(frequency: AutoBackupFrequency) {
    viewModelScope.launch(Dispatchers.IO) {
      autoBackup.setFrequency(frequency)
    }
  }

  fun setAutoBackupKeepCount(keepCount: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      autoBackup.setKeepCount(keepCount)
    }
  }

  fun backUpNow() {
    if (_autoBackupBusy.value) return
    viewModelScope.launch {
      _autoBackupBusy.value = true
      val result = withContext(Dispatchers.IO) {
        autoBackup.runBackupNow()
      }
      _autoBackupBusy.value = false
      refreshBackupKeyStatus()
      when (result) {
        AutoBackupManager.RunResult.Success -> post(str(R.string.vm_auto_backup_saved))
        AutoBackupManager.RunResult.Skipped -> {}
        AutoBackupManager.RunResult.RetryableFailure,
        AutoBackupManager.RunResult.PermanentFailure -> post(str(R.string.vm_auto_backup_failed))
      }
    }
  }

  /** Runs a passphrase operation (slow key derivation) off the main thread. */
  private fun passphraseTask(block: () -> String?, onResult: (Result<String?>) -> Unit) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.Default) { runCatching(block) }
      refreshBackupKeyStatus()
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

  fun unlockDeviceBackupKey(passphrase: String, onResult: (Result<String?>) -> Unit) =
    passphraseTask({ backup.unlockDeviceKey(passphrase.toCharArray()); null }, onResult)

  fun revealRecoveryCode(passphrase: String, onResult: (Result<String?>) -> Unit) =
    passphraseTask({ backup.recoveryCode(passphrase.toCharArray()) }, onResult)

  fun exportBackup(uri: Uri, encrypted: Boolean) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { if (encrypted) backup.exportEncrypted(uri) else backup.exportPlainJson(uri) }
      }
      refreshBackupKeyStatus()
      post(
        result.fold(
          onSuccess = { str(if (encrypted) R.string.vm_backup_saved_encrypted else R.string.vm_backup_saved_plain) },
          onFailure = { str(R.string.vm_backup_save_failed, errorText(it).orEmpty()) },
        ),
      )
    }
  }

  fun openBackup(uri: Uri) {
    _restoreState.value = RestoreUiState.Opening
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { backup.open(uri) } }
      refreshBackupKeyStatus()
      _restoreState.value = result.fold(
        onSuccess = {
          when (it) {
            is BackupManager.OpenResult.Ready -> RestoreUiState.Preview(it)
            is BackupManager.OpenResult.NeedsUnlock -> RestoreUiState.Locked(it)
          }
        },
        onFailure = {
          post(str(R.string.vm_backup_open_failed, errorText(it).orEmpty()))
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
      _restoreState.value = result.fold(
        onSuccess = { RestoreUiState.Preview(it) },
        onFailure = { locked.copy(busy = false, error = errorText(it) ?: str(R.string.vm_backup_unlock_failed)) },
      )
    }
  }

  fun applyRestore(replace: Boolean) {
    val preview = _restoreState.value as? RestoreUiState.Preview ?: return
    _restoreState.value = preview.copy(busy = true)
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { backup.restore(preview.ready.bundle, replace, preview.ready) }
      }
      refreshBackupKeyStatus()
      result.fold(
        onSuccess = { outcome ->
          val conflicts = outcome.conflicts.size
          post(
            when {
              conflicts == 0 -> str(if (replace) R.string.vm_restore_replaced else R.string.vm_restore_merged)
              replace -> plural(R.plurals.vm_restore_replaced_conflicts, conflicts, conflicts)
              else -> plural(R.plurals.vm_restore_merged_conflicts, conflicts, conflicts)
            },
            UndoAction.RestoreBackup(outcome.previous),
          )
          _restoreState.value = if (outcome.recoveryDataKeyForNewPassphrase != null) {
            RestoreUiState.SetNewPassphrase(outcome.recoveryDataKeyForNewPassphrase)
          } else {
            RestoreUiState.Finished
          }
        },
        onFailure = {
          post(str(R.string.vm_restore_failed, errorText(it).orEmpty()))
          _restoreState.value = preview.copy(busy = false)
        },
      )
    }
  }

  fun setPassphraseAfterRecovery(passphrase: String) {
    val state = _restoreState.value as? RestoreUiState.SetNewPassphrase ?: return
    _restoreState.value = state.copy(busy = true, error = null)
    viewModelScope.launch {
      val result = withContext(Dispatchers.Default) {
        runCatching { backup.saveRecoveredKeyWithPassphrase(state.dataKey, passphrase.toCharArray()) }
      }
      refreshBackupKeyStatus()
      result.fold(
        onSuccess = {
          post(str(R.string.backup_passphrase_changed))
          _restoreState.value = RestoreUiState.Finished
        },
        onFailure = {
          _restoreState.value = state.copy(busy = false, error = errorText(it) ?: str(R.string.error_generic))
        },
      )
    }
  }

  fun skipPassphraseAfterRecovery() {
    val state = _restoreState.value as? RestoreUiState.SetNewPassphrase
    state?.dataKey?.fill(0)
    _restoreState.value = RestoreUiState.Finished
  }

  fun resetRestore() {
    when (val state = _restoreState.value) {
      is RestoreUiState.Preview -> state.ready.unlockedDataKey?.fill(0)
      is RestoreUiState.SetNewPassphrase -> state.dataKey.fill(0)
      else -> {}
    }
    _restoreState.value = RestoreUiState.Idle
  }

  // endregion
}
