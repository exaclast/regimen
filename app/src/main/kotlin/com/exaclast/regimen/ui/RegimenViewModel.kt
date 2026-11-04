package com.exaclast.regimen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DailyDoseBoard
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.notifications.DoseNotificationStage
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class TopLevelDestination(val label: String) {
  TODAY("Today"),
  MEDICATIONS("Medications"),
  PRESCRIBERS("Prescribers"),
  CONDITIONS("Conditions"),
}

data class RegimenUiState(
  val selectedDateIso: String = LocalDate.now().toString(),
  val snapshot: RegimenSnapshot = RegimenSnapshot(),
  val dailyBoard: DailyDoseBoard = DailyDoseBoard(
    dateIso = LocalDate.now().toString(),
    bucketGroups = emptyList(),
    prnItems = emptyList(),
    totalScheduledCount = 0,
    totalTakenCount = 0,
    totalSkippedCount = 0,
  ),
)

class RegimenViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = RegimenRepository.getInstance(application)

  private val _selectedDateIso = MutableStateFlow(LocalDate.now().toString())
  val selectedDateIso: StateFlow<String> = _selectedDateIso.asStateFlow()

  private val _selectedDestination = MutableStateFlow(TopLevelDestination.TODAY)
  val selectedDestination: StateFlow<TopLevelDestination> = _selectedDestination.asStateFlow()

  private val _selectedMedicationId = MutableStateFlow<String?>(null)
  val selectedMedicationId: StateFlow<String?> = _selectedMedicationId.asStateFlow()

  private val _selectedPrescriberId = MutableStateFlow<String?>(null)
  val selectedPrescriberId: StateFlow<String?> = _selectedPrescriberId.asStateFlow()

  private val _selectedConditionId = MutableStateFlow<String?>(null)
  val selectedConditionId: StateFlow<String?> = _selectedConditionId.asStateFlow()

  val uiState: StateFlow<RegimenUiState> = combine(
    repository.snapshot,
    _selectedDateIso,
  ) { snapshot, dateIso ->
    val board = DoseEngine.buildDailyBoard(
      dateIso = dateIso,
      medications = snapshot.medications,
      schedules = snapshot.schedules,
      buckets = snapshot.buckets,
      logs = snapshot.doseLogs,
      conditions = snapshot.conditions,
      prescribers = snapshot.prescribers,
    )
    RegimenUiState(
      selectedDateIso = dateIso,
      snapshot = snapshot,
      dailyBoard = board,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = run {
      val s = repository.snapshot.value
      val todayIso = LocalDate.now().toString()
      RegimenUiState(
        selectedDateIso = todayIso,
        snapshot = s,
        dailyBoard = DoseEngine.buildDailyBoard(
          dateIso = todayIso,
          medications = s.medications,
          schedules = s.schedules,
          buckets = s.buckets,
          logs = s.doseLogs,
          conditions = s.conditions,
          prescribers = s.prescribers,
        ),
      )
    },
  )

  init {
    val s = repository.snapshot.value
    DoseNotificationManager.ensureChannels(application)
    DoseNotificationManager.scheduleDailyBucketAlarms(application, s.buckets, s.settings)
    DoseNotificationManager.syncActiveWindowNotifications(application, s)
  }

  fun selectDestination(destination: TopLevelDestination) {
    _selectedDestination.value = destination
  }

  fun selectDate(dateIso: String) {
    _selectedDateIso.value = dateIso
  }

  fun shiftDateByDays(days: Long) {
    val current = runCatching { LocalDate.parse(_selectedDateIso.value) }.getOrDefault(LocalDate.now())
    _selectedDateIso.value = current.plusDays(days).toString()
  }

  fun selectMedication(id: String?) {
    _selectedMedicationId.value = id
  }

  fun selectPrescriber(id: String?) {
    _selectedPrescriberId.value = id
  }

  fun selectCondition(id: String?) {
    _selectedConditionId.value = id
  }

  fun takeScheduledDose(item: ScheduledDoseItem, overrideSite: String? = null, notes: String? = null) {
    repository.logDose(
      medicationId = item.medication.id,
      scheduleId = item.schedule.id,
      bucketId = item.bucket.id,
      scheduledDateIso = item.dateIso,
      status = DoseStatus.TAKEN,
      amountTaken = item.schedule.doseAmount,
      amountUnit = item.schedule.doseUnit,
      siteUsed = overrideSite ?: item.suggestedSite,
      notes = notes,
      existingLogId = item.existingLog?.id,
    )
    DoseNotificationManager.cancelNotificationForItem(getApplication(), item.key)
  }

  fun skipScheduledDose(item: ScheduledDoseItem, notes: String? = null) {
    repository.logDose(
      medicationId = item.medication.id,
      scheduleId = item.schedule.id,
      bucketId = item.bucket.id,
      scheduledDateIso = item.dateIso,
      status = DoseStatus.SKIPPED,
      amountTaken = item.schedule.doseAmount,
      amountUnit = item.schedule.doseUnit,
      siteUsed = null,
      notes = notes,
      existingLogId = item.existingLog?.id,
    )
    DoseNotificationManager.cancelNotificationForItem(getApplication(), item.key)
  }

  fun snoozeScheduledDose(
    item: ScheduledDoseItem,
    minutes: Int = repository.snapshot.value.settings.snoozeMinutes,
  ) {
    val snoozeUntil = System.currentTimeMillis() + minutes * 60_000L
    repository.logDose(
      medicationId = item.medication.id,
      scheduleId = item.schedule.id,
      bucketId = item.bucket.id,
      scheduledDateIso = item.dateIso,
      status = DoseStatus.SNOOZED,
      amountTaken = item.schedule.doseAmount,
      amountUnit = item.schedule.doseUnit,
      siteUsed = item.suggestedSite,
      snoozedUntilMillis = snoozeUntil,
      existingLogId = item.existingLog?.id,
    )
  }

  fun undoScheduledDoseLog(item: ScheduledDoseItem) {
    item.existingLog?.id?.let { repository.deleteDoseLog(it) }
  }

  fun takeAllPendingInBucket(bucketId: String) {
    val board = uiState.value.dailyBoard
    val group = board.bucketGroups.find { it.bucket.id == bucketId } ?: return
    group.items.filter { !it.isCompleted }.forEach { item ->
      takeScheduledDose(item)
    }
  }

  fun logPrnOrCustomDose(
    medication: Medication,
    amount: Double = medication.defaultDoseAmount,
    unit: DoseUnit = medication.defaultDoseUnit,
    siteUsed: String? = null,
    notes: String? = null,
    dateIso: String = _selectedDateIso.value,
  ) {
    repository.logDose(
      medicationId = medication.id,
      scheduleId = null,
      bucketId = null,
      scheduledDateIso = dateIso,
      status = DoseStatus.TAKEN,
      amountTaken = amount,
      amountUnit = unit,
      siteUsed = siteUsed,
      notes = notes,
    )
  }

  fun deleteDoseLog(logId: String) {
    repository.deleteDoseLog(logId)
  }

  fun saveMedication(medication: Medication, schedules: List<MedicationSchedule>? = null) {
    repository.saveMedication(medication, schedules)
    _selectedMedicationId.value = medication.id
  }

  fun saveSchedule(schedule: MedicationSchedule) {
    repository.saveSchedule(schedule)
  }

  fun deleteSchedule(scheduleId: String) {
    repository.deleteSchedule(scheduleId)
  }

  fun deleteMedication(medicationId: String) {
    if (_selectedMedicationId.value == medicationId) {
      _selectedMedicationId.value = null
    }
    repository.deleteMedication(medicationId)
  }

  fun savePrescriber(prescriber: Prescriber) {
    repository.savePrescriber(prescriber)
    _selectedPrescriberId.value = prescriber.id
  }

  fun deletePrescriber(prescriberId: String) {
    if (_selectedPrescriberId.value == prescriberId) {
      _selectedPrescriberId.value = null
    }
    repository.deletePrescriber(prescriberId)
  }

  fun saveCondition(condition: Condition) {
    repository.saveCondition(condition)
    _selectedConditionId.value = condition.id
  }

  fun deleteCondition(conditionId: String) {
    if (_selectedConditionId.value == conditionId) {
      _selectedConditionId.value = null
    }
    repository.deleteCondition(conditionId)
  }

  fun saveBucket(bucket: TimeOfDayBucket) {
    repository.saveBucket(bucket)
  }

  fun deleteBucket(bucketId: String) {
    repository.deleteBucket(bucketId)
  }

  fun reorderBuckets(buckets: List<TimeOfDayBucket>) {
    repository.reorderBuckets(buckets)
  }

  fun resetBucketsToDefault() {
    repository.resetBucketsToDefault()
  }

  fun saveSettings(settings: AppSettings) {
    repository.saveSettings(settings)
  }

  fun refreshPermissionsAndAlarms() {
    val s = repository.snapshot.value
    DoseNotificationManager.scheduleDailyBucketAlarms(getApplication(), s.buckets, s.settings)
    DoseNotificationManager.syncActiveWindowNotifications(getApplication(), s)
  }

  fun clearAllUserData() {
    _selectedMedicationId.value = null
    _selectedPrescriberId.value = null
    _selectedConditionId.value = null
    repository.clearAllUserData()
  }

  fun resetToSampleData() {
    _selectedMedicationId.value = null
    _selectedPrescriberId.value = null
    _selectedConditionId.value = null
    repository.resetToSampleData()
  }

  fun sendTestReminder(
    item: ScheduledDoseItem,
    stage: DoseNotificationStage = DoseNotificationStage.WINDOW_START_PERSISTENT,
  ): Boolean {
    return DoseNotificationManager.showDoseWindowNotification(
      context = getApplication(),
      item = item,
      stage = stage,
      settings = repository.snapshot.value.settings,
    )
  }

  fun exportBackupJson(): String = repository.exportBackupJson()

  fun importBackupJson(rawJson: String): Result<Int> = repository.importBackupJson(rawJson)
}

