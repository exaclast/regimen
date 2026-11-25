package com.exaclast.regimen.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.WindowDeletion
import com.exaclast.regimen.model.AllergenType
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.ItemSource
import com.exaclast.regimen.model.KeyIngredient
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.RegimenItem
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.Severity
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.Supplement
import com.exaclast.regimen.model.SyncDevice
import com.exaclast.regimen.model.SyringeType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.sync.DeviceIdentity
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RegimenSnapshot(
  val settings: AppSettings = AppSettings(),
  val medications: List<RegimenItem> = emptyList(),
  val deletedMedications: List<RegimenItem> = emptyList(),
  val providers: List<Provider> = emptyList(),
  val indications: List<Indication> = emptyList(),
  val buckets: List<TimeOfDayBucket> = TimeOfDayBucket.defaultBuckets(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
) {
  val allMedications: List<RegimenItem>
    get() = medications + deletedMedications

  fun medication(id: String): RegimenItem? =
    medications.find { it.id == id } ?: deletedMedications.find { it.id == id }
  /**
   * Providers and indications are optional. Until the user chooses, they're shown only when
   * records exist: new installs start minimal, and nobody loses sight of data they entered.
   */
  val tracksProviders: Boolean
    get() = settings.trackProviders ?: providers.any { it.deletedAtEpochMillis == null }

  val tracksIndications: Boolean
    get() = settings.trackIndications ?: indications.any { it.deletedAtEpochMillis == null }

  fun isArchived(indication: Indication, today: LocalDate = LocalDate.now()): Boolean =
    IndicationTimeline.isArchived(indication, today, medications, schedules)

  fun indicationEnd(indication: Indication): IndicationTimeline.End? =
    IndicationTimeline.end(indication, medications, schedules)
}

/**
 * Singleton offline-first repository backed by SQLite, architected with soft-delete tombstones,
 * UUIDs, and JSON export/import so future Cloud Sync providers can plug in seamlessly.
 */
class RegimenRepository private constructor(context: Context) {

  private val appContext = context.applicationContext
  private val db = RegimenDatabase(appContext)
  private val prefs = appContext.getSharedPreferences("regimen_prefs", Context.MODE_PRIVATE)
  val deviceIdentity = DeviceIdentity(appContext)
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val _snapshot = MutableStateFlow(RegimenSnapshot())
  val snapshot: StateFlow<RegimenSnapshot> = _snapshot.asStateFlow()

  init {
    ensureInitializedAndLoaded()
  }

  @Synchronized
  private fun ensureInitializedAndLoaded() {
    val existingBuckets = db.getAllBuckets(includeDeleted = true)
    if (existingBuckets.isEmpty()) {
      TimeOfDayBucket.defaultBuckets().forEach { db.upsertBucket(it) }
    }
    if (!prefs.getBoolean(KEY_SEEDED_INITIAL, false)) {
      // Don't load demo data by default; leave data empty for initial user setup
      prefs.edit().putBoolean(KEY_SEEDED_INITIAL, true).apply()
    }
    // Load only. Every caller refreshes for itself, and a receiver must do so first with the window
    // it's about to alert for: refreshing here would post that notification silently beforehand.
    _snapshot.value = readSnapshot()
  }

  private fun loadSettings(): AppSettings {
    val raw = prefs.getString(KEY_APP_SETTINGS, null) ?: return AppSettings()
    return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
  }

  private fun loadPrimaryClaim(): PrimaryClaim? {
    val raw = prefs.getString(KEY_PRIMARY_CLAIM, null) ?: return null
    return runCatching { json.decodeFromString<PrimaryClaim>(raw) }.getOrNull()
  }

  private fun loadSyncDevices(): List<SyncDevice> {
    val raw = prefs.getString(KEY_SYNC_DEVICES, null) ?: return emptyList()
    return runCatching { json.decodeFromString<List<SyncDevice>>(raw) }.getOrDefault(emptyList())
  }

  @Synchronized
  fun saveSettings(settings: AppSettings) {
    // Shared settings sync across devices; stamp when their values change so merges can tell.
    val previous = loadSettings()
    val stamped = if (previous.shared().sameValues(settings.shared())) {
      settings.copy(sharedSettingsUpdatedAtEpochMillis = previous.sharedSettingsUpdatedAtEpochMillis)
    } else {
      settings.copy(sharedSettingsUpdatedAtEpochMillis = System.currentTimeMillis())
    }
    prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(stamped)).apply()
    reloadSnapshot()
  }

  private fun readSnapshot(): RegimenSnapshot {
    val (liveMeds, deletedMeds) = db.getAllMedications(includeDeleted = true)
      .partition { it.deletedAtEpochMillis == null }
    return RegimenSnapshot(
      settings = loadSettings(),
      medications = liveMeds,
      deletedMedications = deletedMeds,
      providers = db.getAllProviders(),
      indications = db.getAllIndications(),
      buckets = db.getAllBuckets().ifEmpty { TimeOfDayBucket.defaultBuckets() },
      schedules = db.getAllSchedules(),
      doseLogs = db.getAllDoseLogs(),
    )
  }

  @Synchronized
  fun reloadSnapshot(notifyAutoBackup: Boolean = true) {
    val nextSnapshot = readSnapshot()
    _snapshot.value = nextSnapshot
    DoseNotificationManager.refresh(appContext, nextSnapshot)
    if (notifyAutoBackup) {
      AutoBackupManager.notifyDataChanged(appContext)
    }
  }

  @Synchronized
  fun saveMedication(medication: RegimenItem, schedulesForMedication: List<MedicationSchedule>? = null) {
    val now = System.currentTimeMillis()
    val normalized: RegimenItem = when (medication) {
      is Medication -> medication.copy(
        ndc11 = Medication.normalizeNdc11(medication.ndc11),
        // A blank NDC on an adjustment means "cleared" and must stay blank, not become null (carry over).
        adjustments = medication.adjustments.map { adjustment ->
          adjustment.ndc11?.takeIf { it.isNotBlank() }?.let { adjustment.copy(ndc11 = Medication.normalizeNdc11(it)) } ?: adjustment
        },
        updatedAtEpochMillis = now,
      )
      is Supplement -> medication.copy(updatedAtEpochMillis = now)
    }
    db.upsertMedication(normalized)

    if (schedulesForMedication != null) {
      val currentSchedules = db.getAllSchedules().filter { it.medicationId == medication.id }
      val incomingIds = schedulesForMedication.map { it.id }.toSet()
      currentSchedules.filter { !incomingIds.contains(it.id) }.forEach { removed ->
        db.upsertSchedule(removed.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
      }
      schedulesForMedication.forEach { sched ->
        db.upsertSchedule(sched.copy(medicationId = medication.id, updatedAtEpochMillis = now))
      }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun deleteMedication(medicationId: String) {
    val now = System.currentTimeMillis()
    val med = _snapshot.value.medications.find { it.id == medicationId } ?: return
    cancelSnoozeAlarms(_snapshot.value.doseLogs.filter { it.medicationId == medicationId })
    db.upsertMedication(med.withDeletedAt(now))
    _snapshot.value.schedules.filter { it.medicationId == medicationId }.forEach { sched ->
      db.upsertSchedule(sched.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  @Synchronized
  fun saveSchedule(schedule: MedicationSchedule) {
    val now = System.currentTimeMillis()
    db.upsertSchedule(schedule.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  /** Atomically saves a schedule edit (which may update medication adjustments and version schedules). */
  @Synchronized
  fun saveScheduleEdit(medication: RegimenItem?, schedules: List<MedicationSchedule>) {
    if (medication == null && schedules.isEmpty()) return
    val now = System.currentTimeMillis()
    db.inTransaction {
      medication?.let { db.upsertMedication(it.withUpdatedAt(now)) }
      schedules.forEach { db.upsertSchedule(it.copy(updatedAtEpochMillis = now)) }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun deleteSchedule(scheduleId: String) {
    val now = System.currentTimeMillis()
    val sched = _snapshot.value.schedules.find { it.id == scheduleId } ?: return
    cancelSnoozeAlarms(_snapshot.value.doseLogs.filter { it.scheduleId == scheduleId })
    db.upsertSchedule(sched.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    reloadSnapshot()
  }

  @Synchronized
  fun saveProvider(provider: Provider) {
    val now = System.currentTimeMillis()
    db.upsertProvider(provider.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  @Synchronized
  fun deleteProvider(providerId: String) {
    val now = System.currentTimeMillis()
    val provider = _snapshot.value.providers.find { it.id == providerId } ?: return
    db.upsertProvider(provider.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    // Unlink medications it prescribed and indications it was managing.
    _snapshot.value.medications.filter { it.prescriberId == providerId }.forEach { med ->
      db.upsertMedication(med.withPrescriberId(null, now))
    }
    _snapshot.value.indications.filter { providerId in it.providerIds }.forEach { indication ->
      db.upsertIndication(indication.copy(providerIds = indication.providerIds - providerId, updatedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  /** Sets which indications [providerId] helps manage (edited from the provider's page). */
  @Synchronized
  fun setProviderIndications(providerId: String, indicationIds: Set<String>) {
    val now = System.currentTimeMillis()
    _snapshot.value.indications.forEach { indication ->
      val linked = providerId in indication.providerIds
      val shouldLink = indication.id in indicationIds
      if (linked != shouldLink) {
        val ids = if (shouldLink) indication.providerIds + providerId else indication.providerIds - providerId
        db.upsertIndication(indication.copy(providerIds = ids, updatedAtEpochMillis = now))
      }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun saveIndication(indication: Indication) {
    val now = System.currentTimeMillis()
    // Resolving without an end date ends it today (or on the start date if later), so it files under past indications with a date.
    val ended = if (indication.status == IndicationStatus.RESOLVED && indication.endDateIso.isNullOrBlank()) {
      val today = LocalDate.now()
      val start = indication.diagnosedDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
      val resolvedDate = if (start != null && start.isAfter(today)) start else today
      indication.copy(endDateIso = resolvedDate.toString())
    } else {
      indication
    }
    db.upsertIndication(ended.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  @Synchronized
  fun deleteIndication(indicationId: String) {
    val now = System.currentTimeMillis()
    val indication = _snapshot.value.indications.find { it.id == indicationId } ?: return
    db.upsertIndication(indication.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    // Unlink any medications pointing to this indication
    _snapshot.value.medications.filter { indicationId in it.indicationIds }.forEach { med ->
      db.upsertMedication(med.withIndicationIds(med.indicationIds - indicationId, now))
    }
    reloadSnapshot()
  }

  /** Sets which providers help manage [indicationId] (edited from the indication's page). */
  @Synchronized
  fun setIndicationProviders(indicationId: String, providerIds: Set<String>) {
    val indication = _snapshot.value.indications.find { it.id == indicationId } ?: return
    if (indication.providerIds.toSet() == providerIds) return
    db.upsertIndication(
      indication.copy(providerIds = providerIds.sorted(), updatedAtEpochMillis = System.currentTimeMillis())
    )
    reloadSnapshot()
  }

  /** Sets which medications treat [indicationId] (edited from the indication's page). */
  @Synchronized
  fun setIndicationMedications(indicationId: String, medicationIds: Set<String>) {
    val now = System.currentTimeMillis()
    _snapshot.value.medications.forEach { med ->
      val linked = indicationId in med.indicationIds
      val shouldLink = med.id in medicationIds
      if (linked != shouldLink) {
        val ids = if (shouldLink) med.indicationIds + indicationId else med.indicationIds - indicationId
        db.upsertMedication(med.withIndicationIds(ids, now))
      }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun saveBucket(bucket: TimeOfDayBucket) {
    db.upsertBucket(bucket.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    reloadSnapshot()
  }

  /** Deletes a window, moving its doses to [moveToBucketId] (or the nearest window if none is given). */
  @Synchronized
  fun deleteBucket(bucketId: String, moveToBucketId: String? = null) {
    val now = System.currentTimeMillis()
    val buckets = db.getAllBuckets()
    val window = buckets.find { it.id == bucketId } ?: return
    val targetId = moveToBucketId ?: WindowDeletion.nearestWindow(window, buckets - window)?.id
    val plan = targetId?.let {
      WindowDeletion.plan(bucketId, it, db.getAllSchedules(), db.getAllDoseLogs(), now)
    }
    db.inTransaction {
      plan?.scheduleChanges?.forEach { db.upsertSchedule(it.after) }
      plan?.movedLogs?.forEach { db.upsertDoseLog(it) }
      db.upsertBucket(window.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    }
    DoseNotificationManager.cancelBucketAlarms(appContext, bucketId)
    // A moved snooze has a new alarm identity: the refresh arms it, so drop the old one here.
    plan?.movedLogs?.filter { it.status == DoseStatus.SNOOZED }?.forEach { moved ->
      itemKeyFor(moved.copy(bucketId = bucketId))?.let { key ->
        DoseNotificationManager.cancelSnoozeAlarm(appContext, key, bucketId)
      }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun resetBucketsToDefault() {
    val now = System.currentTimeMillis()
    val allExisting = db.getAllBuckets(includeDeleted = true).associateBy { it.id }
    val current = allExisting.values.filter { it.deletedAtEpochMillis == null }
    val usedWindowIds = db.getAllSchedules().flatMapTo(HashSet()) { it.bucketIds }
    val restored = TimeOfDayBucket.windowsAfterRestoringDefaults(current, usedWindowIds)
    val keepIds = restored.mapTo(HashSet()) { it.id }
    val removed = current.filter { it.id !in keepIds }
    db.inTransaction {
      removed.forEach { db.upsertBucket(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      restored.forEach { bucket ->
        val previous = allExisting[bucket.id]
        val stamped = if (previous?.deletedAtEpochMillis != null) {
          // Re-creating a previously deleted default must beat its tombstone on sync.
          bucket.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = null)
        } else {
          bucket
        }
        db.upsertBucket(stamped)
      }
    }
    removed.forEach { DoseNotificationManager.cancelBucketAlarms(appContext, it.id) }
    reloadSnapshot()
  }

  data class DoseLogRequest(
    val medicationId: String,
    val scheduleId: String? = null,
    val bucketId: String? = null,
    val scheduledDateIso: String = LocalDate.now().toString(),
    val status: DoseStatus = DoseStatus.TAKEN,
    val amountTaken: Double? = null,
    val amountUnit: DoseUnit? = null,
    val siteUsed: String? = null,
    val notes: String? = null,
    val snoozedUntilMillis: Long? = null,
    val existingLogId: String? = null,
    /** When the dose was actually taken; defaults to now (e.g. "taken on time" back-dates it). */
    val actionTimestampMillis: Long? = null,
  )

  @Synchronized
  fun logDose(
    medicationId: String,
    scheduleId: String? = null,
    bucketId: String? = null,
    scheduledDateIso: String = LocalDate.now().toString(),
    status: DoseStatus = DoseStatus.TAKEN,
    amountTaken: Double? = null,
    amountUnit: DoseUnit? = null,
    siteUsed: String? = null,
    notes: String? = null,
    snoozedUntilMillis: Long? = null,
    existingLogId: String? = null,
    /** When the dose was actually taken; defaults to now (e.g. "taken on time" back-dates it). */
    actionTimestampMillis: Long? = null,
  ): DoseLog? = logDoses(
    listOf(
      DoseLogRequest(
        medicationId = medicationId,
        scheduleId = scheduleId,
        bucketId = bucketId,
        scheduledDateIso = scheduledDateIso,
        status = status,
        amountTaken = amountTaken,
        amountUnit = amountUnit,
        siteUsed = siteUsed,
        notes = notes,
        snoozedUntilMillis = snoozedUntilMillis,
        existingLogId = existingLogId,
        actionTimestampMillis = actionTimestampMillis,
      ),
    ),
  ).firstOrNull()

  /** Writes a batch of dose logs in one database transaction and one snapshot/alarm refresh. */
  @Synchronized
  fun logDoses(requests: List<DoseLogRequest>): List<DoseLog> {
    if (requests.isEmpty()) return emptyList()
    val state = _snapshot.value
    val medMap = state.medications.associateBy { it.id }
    val scheduleMap = state.schedules.associateBy { it.id }
    val workingLogs = state.doseLogs.toMutableList()
    val existingById = state.doseLogs.associateBy { it.id }
    val now = System.currentTimeMillis()

    val created = requests.mapIndexedNotNull { index, req ->
      val stored = medMap[req.medicationId] ?: return@mapIndexedNotNull null
      // Without an explicit amount, use what applied on the dose's own day (titration steps).
      val day = runCatching { LocalDate.parse(req.scheduledDateIso) }.getOrDefault(LocalDate.now())
      val med = Adjustments.asOf(stored, day)
      val schedule = req.scheduleId?.let { scheduleMap[it] }?.let { Adjustments.scheduleOn(stored, it, day) }
      val resolvedAmount = req.amountTaken ?: schedule?.doseAmount ?: med.defaultDoseAmount
      val resolvedUnit = req.amountUnit ?: schedule?.doseUnit ?: med.defaultDoseUnit
      val resolvedSite = if (req.status == DoseStatus.TAKEN) {
        req.siteUsed ?: DoseEngine.suggestSite(med, workingLogs)
      } else {
        req.siteUsed
      }
      val existing = req.existingLogId?.let { existingById[it] }

      val log = DoseLog(
        id = req.existingLogId ?: UUID.randomUUID().toString(),
        medicationId = req.medicationId,
        scheduleId = req.scheduleId,
        bucketId = req.bucketId,
        scheduledDateIso = req.scheduledDateIso,
        actionTimestampMillis = req.actionTimestampMillis ?: (now + index),
        status = req.status,
        snoozedUntilMillis = req.snoozedUntilMillis,
        amountTaken = resolvedAmount,
        amountUnit = resolvedUnit,
        siteUsed = resolvedSite,
        notes = req.notes,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
        updatedAtEpochMillis = now,
      )
      workingLogs.removeAll { it.id == log.id }
      workingLogs.add(log)
      log
    }
    if (created.isEmpty()) return emptyList()

    db.inTransaction {
      created.forEach { db.upsertDoseLog(it) }
    }
    created.forEach { log ->
      if (log.scheduleId != null && log.bucketId != null) {
        val itemKey = "${log.scheduleId}_${log.bucketId}_${log.scheduledDateIso}"
        DoseNotificationManager.cancelNotificationForItem(appContext, itemKey)
        // A new snooze is armed by the refresh in reloadSnapshot.
        if (log.status != DoseStatus.SNOOZED) {
          DoseNotificationManager.cancelSnoozeAlarm(appContext, itemKey, log.bucketId)
        }
      }
    }
    reloadSnapshot()
    return created
  }

  @Synchronized
  fun deleteDoseLog(logId: String) {
    val now = System.currentTimeMillis()
    val log = _snapshot.value.doseLogs.find { it.id == logId } ?: return
    db.upsertDoseLog(log.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    itemKeyFor(log)?.let { key -> DoseNotificationManager.cancelSnoozeAlarm(appContext, key, log.bucketId) }
    reloadSnapshot()
  }

  /** Saves an edited dose log (time, amount, site, notes), stamping it so the change syncs. */
  @Synchronized
  fun updateDoseLog(log: DoseLog) {
    db.upsertDoseLog(log.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    reloadSnapshot()
  }

  /**
   * Restores [log] exactly as it was (used by Undo): un-deletes it, or puts back the state a newer action
   * overwrote. A restored future snooze is re-armed by the refresh in reloadSnapshot.
   */
  @Synchronized
  fun restoreDoseLog(log: DoseLog) {
    restoreAndDeleteDoseLogs(toRestore = listOf(log), toDeleteIds = emptyList())
  }

  /** Batches restoring prior logs and soft-deleting newly written ones (used by Undo). */
  @Synchronized
  fun restoreAndDeleteDoseLogs(toRestore: List<DoseLog>, toDeleteIds: List<String>) {
    if (toRestore.isEmpty() && toDeleteIds.isEmpty()) return
    val now = System.currentTimeMillis()
    val currentLogs = _snapshot.value.doseLogs.associateBy { it.id }
    val toDelete = toDeleteIds.mapNotNull { currentLogs[it] }
    db.inTransaction {
      toRestore.forEach { log ->
        db.upsertDoseLog(log.copy(deletedAtEpochMillis = null, updatedAtEpochMillis = now))
      }
      toDelete.forEach { log ->
        db.upsertDoseLog(log.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
      }
    }
    toRestore.forEach { restored ->
      val stillSnoozed = restored.status == DoseStatus.SNOOZED && (restored.snoozedUntilMillis ?: 0L) > now
      if (!stillSnoozed) {
        itemKeyFor(restored)?.let { key -> DoseNotificationManager.cancelSnoozeAlarm(appContext, key, restored.bucketId) }
      }
    }
    toDelete.forEach { log ->
      itemKeyFor(log)?.let { key -> DoseNotificationManager.cancelSnoozeAlarm(appContext, key, log.bucketId) }
    }
    reloadSnapshot()
  }

  /** Un-deletes a schedule (used by Undo). */
  @Synchronized
  fun restoreSchedule(schedule: MedicationSchedule) {
    db.upsertSchedule(schedule.copy(deletedAtEpochMillis = null, updatedAtEpochMillis = System.currentTimeMillis()))
    reloadSnapshot()
  }

  private fun itemKeyFor(log: DoseLog): String? {
    val scheduleId = log.scheduleId ?: return null
    val bucketId = log.bucketId ?: return null
    return "${scheduleId}_${bucketId}_${log.scheduledDateIso}"
  }

  /** Counts undecodable rows across all database tables to detect corruption before backup or sync. */
  fun undecodableRowCount(): Int = db.undecodableRowCount()

  /**
   * Everything on this device as a bundle (the input to export and sync), including deletion
   * tombstones so a device that never saw a record still learns it was deleted.
   */
  @Synchronized
  fun currentBundle(): RegimenBackupBundle {
    val settings = loadSettings()
    val storedDevices = loadSyncDevices()
    val selfExisting = storedDevices.firstOrNull { it.id == deviceIdentity.deviceId }
    val selfDevice = deviceIdentity.asSyncDevice(lastSyncedEpochMillis = selfExisting?.lastSyncedEpochMillis ?: 0L).let {
      if (selfExisting != null && selfExisting.firstSeenEpochMillis > 0L) {
        it.copy(firstSeenEpochMillis = minOf(selfExisting.firstSeenEpochMillis, it.firstSeenEpochMillis))
      } else {
        it
      }
    }
    val devices = (storedDevices.filterNot { it.id == selfDevice.id } + selfDevice)
      .sortedWith(compareBy({ it.firstSeenEpochMillis }, { it.id }))
    val allItems = db.getAllMedications(includeDeleted = true)
    return RegimenBackupBundle(
      exportedAtEpochMillis = System.currentTimeMillis(),
      sourceDeviceId = deviceIdentity.deviceId,
      settings = settings,
      sharedSettings = settings.shared(),
      devices = devices,
      primary = loadPrimaryClaim(),
      medications = allItems.filterIsInstance<Medication>(),
      supplements = allItems.filterIsInstance<Supplement>(),
      providers = db.getAllProviders(includeDeleted = true),
      indications = db.getAllIndications(includeDeleted = true),
      buckets = db.getAllBuckets(includeDeleted = true).ifEmpty { TimeOfDayBucket.defaultBuckets() },
      schedules = db.getAllSchedules(includeDeleted = true),
      doseLogs = db.getAllDoseLogs(includeDeleted = true),
    )
  }

  /**
   * Writes [bundle] to this device.
   * - [replace] = true: this device's records become exactly the bundle's, and its device settings
   *   are restored too (a full restore, e.g. onto a new phone).
   * - [replace] = false: records are upserted (the bundle is already a merge result) and only the
   *   shared settings are taken; reminder and display settings stay as they are.
   */
  @Synchronized
  fun applyBundle(bundle: RegimenBackupBundle, replace: Boolean) {
    BackupFiles.validate(bundle)
    val current = loadSettings()
    val shared = bundle.sharedSettings
    val settings = if (replace) {
      bundle.settings.withShared(shared).copy(
        // Don't re-show onboarding/permission prompts this device has already been through.
        permissionsPromptDismissedVersion = maxOf(
          current.permissionsPromptDismissedVersion,
          bundle.settings.permissionsPromptDismissedVersion,
        ),
      )
    } else {
      current.withShared(shared)
    }

    val nextLiveSnoozeIds = bundle.doseLogs
      .filter { it.deletedAtEpochMillis == null && it.status == DoseStatus.SNOOZED }
      .mapTo(HashSet()) { it.id }
    val obsoleteSnoozes = db.getAllDoseLogs().filter { log ->
      log.status == DoseStatus.SNOOZED && (replace || log.id !in nextLiveSnoozeIds)
    }
    val previousLiveBucketIds = db.getAllBuckets().mapTo(HashSet()) { it.id }
    val removedBucketIds = db.inTransaction {
      if (replace) db.clearUserRecords(includeBuckets = true)
      val buckets = bundle.buckets.ifEmpty { TimeOfDayBucket.defaultBuckets() }
      val keepBucketIds = buckets.mapTo(HashSet()) { it.id }
      if (!replace) {
        db.getAllBuckets(includeDeleted = true)
          .filter { it.id !in keepBucketIds }
          .forEach { db.deleteBucket(it.id) }
      }
      buckets.forEach { db.upsertBucket(it) }
      bundle.providers.forEach { db.upsertProvider(it) }
      bundle.indications.forEach { db.upsertIndication(it) }
      bundle.medications.forEach { db.upsertMedication(it) }
      bundle.supplements.forEach { db.upsertMedication(it) }
      bundle.schedules.forEach { db.upsertSchedule(it) }
      bundle.doseLogs.forEach { db.upsertDoseLog(it) }
      val liveAfterIds = buckets.filter { it.deletedAtEpochMillis == null }.mapTo(HashSet()) { it.id }
      previousLiveBucketIds.filter { it !in liveAfterIds }
    }
    // Commit settings and sync metadata only after the database transaction succeeds so a failed restore never leaves them out of step.
    prefs.edit().apply {
      putString(KEY_APP_SETTINGS, json.encodeToString(settings))
      if (bundle.primary != null) {
        putString(KEY_PRIMARY_CLAIM, json.encodeToString(bundle.primary))
      } else {
        remove(KEY_PRIMARY_CLAIM)
      }
      putString(KEY_SYNC_DEVICES, json.encodeToString(bundle.devices))
    }.commit()
    cancelSnoozeAlarms(obsoleteSnoozes)
    removedBucketIds.forEach { DoseNotificationManager.cancelBucketAlarms(appContext, it) }
    reloadSnapshot()
  }

  private fun cancelSnoozeAlarms(logs: Iterable<DoseLog>) {
    logs.forEach { log ->
      if (log.status == DoseStatus.SNOOZED && log.scheduleId != null && log.bucketId != null) {
        val itemKey = "${log.scheduleId}_${log.bucketId}_${log.scheduledDateIso}"
        DoseNotificationManager.cancelNotificationForItem(appContext, itemKey)
        DoseNotificationManager.cancelSnoozeAlarm(appContext, itemKey, log.bucketId)
      }
    }
  }

  /** Soft-deletes every live user record so existing tombstones stay intact for sync. */
  private fun softDeleteAllUserRecords(now: Long) {
    db.inTransaction {
      db.getAllDoseLogs().forEach { db.upsertDoseLog(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllSchedules().forEach { db.upsertSchedule(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllMedications().forEach { db.upsertMedication(it.withDeletedAt(now)) }
      db.getAllProviders().forEach { db.upsertProvider(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllIndications().forEach { db.upsertIndication(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
    }
  }

  @Synchronized
  fun clearAllUserData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
    cancelSnoozeAlarms(_snapshot.value.doseLogs)
    softDeleteAllUserRecords(System.currentTimeMillis())
    reloadSnapshot(notifyAutoBackup = false)
  }

  @Synchronized
  fun resetToSampleData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
    cancelSnoozeAlarms(_snapshot.value.doseLogs)
    val now = System.currentTimeMillis()
    softDeleteAllUserRecords(now)
    seedInitialSampleData(now)
    // Suppress reminders and alarms while demo data is loaded to prevent ringing real alarms for fictional meds.
    val current = loadSettings()
    val suppressed = current.copy(notificationsEnabled = false, alarmsEnabled = false)
    prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(suppressed)).apply()
    reloadSnapshot()
  }

  private fun seedInitialSampleData(now: Long = System.currentTimeMillis()) {
    val today = LocalDate.now()
    val yesterdayIso = today.minusDays(1).toString()

    // Ensure the windows referenced by demo schedules exist and are live.
    val liveBucketIds = db.getAllBuckets().mapTo(HashSet()) { it.id }
    val requiredDefaultIds = setOf(
      TimeOfDayBucket.BUCKET_MORNING,
      TimeOfDayBucket.BUCKET_EVENING,
      TimeOfDayBucket.BUCKET_BEDTIME,
    )
    TimeOfDayBucket.defaultBuckets()
      .filter { it.id in requiredDefaultIds && it.id !in liveBucketIds }
      .forEach { db.upsertBucket(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = null)) }

    // 1. Sample Providers (PCP/regular, regular specialist, as-needed specialist, and past)
    val pcp = Provider(
      id = UUID.randomUUID().toString(),
      name = "Dr. Elena Rostova, MD",
      specialty = "Family Medicine & Primary Care",
      practice = "Metro Health Family Practice",
      phone = "555-014-8820",
      email = "erostova@metrohealth.example.org",
      isPcp = true,
      seenRegularly = true,
      careStartDateIso = today.minusYears(1).toString(),
      notes = "Annual wellness and primary care",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val endocrinologist = Provider(
      id = UUID.randomUUID().toString(),
      name = "Dr. Marcus Vance, MD",
      specialty = "Endocrinology & Diabetes Care",
      practice = "Valley Endocrine Specialists",
      phone = "555-019-3341",
      email = "mvance@valleyendocrine.example.org",
      isPcp = false,
      seenRegularly = true,
      careStartDateIso = today.minusMonths(6).toString(),
      notes = "Quarterly diabetic management review",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val allergist = Provider(
      id = UUID.randomUUID().toString(),
      name = "Dr. Priya Nair, MD",
      specialty = "Allergy & Immunology",
      practice = "Cascade Allergy Clinic",
      phone = "555-017-4490",
      isPcp = false,
      seenRegularly = false,
      careStartDateIso = today.minusYears(2).toString(),
      notes = "Consult as needed for allergy action plan updates",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val pastOrthopedist = Provider(
      id = UUID.randomUUID().toString(),
      name = "Dr. Samuel Park, MD",
      specialty = "Orthopedic Surgery",
      practice = "Harbor Orthopedics",
      phone = "555-012-7703",
      careStartDateIso = today.minusYears(2).toString(),
      careEndDateIso = today.minusMonths(8).toString(),
      notes = "Released from follow-up after ankle recovery",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    db.upsertProvider(pcp)
    db.upsertProvider(endocrinologist)
    db.upsertProvider(allergist)
    db.upsertProvider(pastOrthopedist)

    // 2. Sample Indications (chronic, secondary, acute, suspected, preventive, allergies, intolerance, resolved)
    val condDiabetes = Indication(
      id = UUID.randomUUID().toString(),
      name = "Type 2 Diabetes Mellitus",
      icd10 = "E11.9",
      details = "Glycemic control, fasting blood glucose monitoring, and basal insulin regimen.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.MANAGED,
      severity = Severity.MODERATE,
      diagnosedDateIso = today.minusMonths(6).toString(),
      providerIds = listOf(endocrinologist.id, pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condNeuropathy = Indication(
      id = UUID.randomUUID().toString(),
      name = "Peripheral Neuropathy",
      icd10 = "E11.42",
      details = "Mild tingling in feet secondary to diabetes; B12 supplementation started.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.MANAGED,
      severity = Severity.MILD,
      diagnosedDateIso = today.minusMonths(3).toString(),
      primaryIds = listOf(condDiabetes.id),
      providerIds = listOf(endocrinologist.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condSinusitis = Indication(
      id = UUID.randomUUID().toString(),
      name = "Acute Bacterial Sinusitis",
      icd10 = "J01.90",
      details = "10-day oral antibiotic course for acute bacterial sinus infection.",
      kind = IndicationKind.ACUTE,
      status = IndicationStatus.ACTIVE,
      severity = Severity.MODERATE,
      diagnosedDateIso = today.minusDays(2).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condVertigo = Indication(
      id = UUID.randomUUID().toString(),
      name = "Benign Positional Vertigo",
      details = "Brief dizziness episodes on waking; scheduled for vestibular evaluation.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.ACTIVE,
      suspected = true,
      severity = Severity.MILD,
      diagnosedDateIso = today.minusWeeks(2).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condAsthmaPrev = Indication(
      id = UUID.randomUUID().toString(),
      name = "Seasonal Asthma Prevention",
      details = "Fall ragweed season airway prophylaxis plan.",
      kind = IndicationKind.PREVENTIVE,
      status = IndicationStatus.ACTIVE,
      diagnosedDateIso = today.minusMonths(1).toString(),
      providerIds = listOf(pcp.id, allergist.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val allergySulfa = Indication(
      id = UUID.randomUUID().toString(),
      name = "Sulfamethoxazole (Sulfa drugs)",
      kind = IndicationKind.ALLERGY,
      status = IndicationStatus.ACTIVE,
      allergenType = AllergenType.DRUG,
      severity = Severity.SEVERE,
      reaction = "Generalized hives and facial swelling",
      diagnosedDateIso = today.minusYears(4).toString(),
      providerIds = listOf(allergist.id, pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val allergyPollen = Indication(
      id = UUID.randomUUID().toString(),
      name = "Ragweed Pollen",
      kind = IndicationKind.ALLERGY,
      status = IndicationStatus.ACTIVE,
      allergenType = AllergenType.ENVIRONMENTAL,
      severity = Severity.MODERATE,
      reaction = "Allergic conjunctivitis and sneezing",
      providerIds = listOf(allergist.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val allergyPeanut = Indication(
      id = UUID.randomUUID().toString(),
      name = "Peanuts",
      kind = IndicationKind.ALLERGY,
      status = IndicationStatus.ACTIVE,
      allergenType = AllergenType.FOOD,
      severity = Severity.LIFE_THREATENING,
      reaction = "Anaphylaxis — carries epinephrine auto-injector",
      diagnosedDateIso = today.minusYears(10).toString(),
      providerIds = listOf(allergist.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val intoleranceNsaid = Indication(
      id = UUID.randomUUID().toString(),
      name = "Ibuprofen / NSAIDs",
      kind = IndicationKind.INTOLERANCE,
      status = IndicationStatus.ACTIVE,
      allergenType = AllergenType.DRUG,
      severity = Severity.MODERATE,
      reaction = "Gastric upset and heartburn",
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    // A past episode, to show archived indications.
    val condStrep = Indication(
      id = UUID.randomUUID().toString(),
      name = "Strep Throat",
      icd10 = "J02.0",
      details = "Treated with a 10-day amoxicillin course.",
      kind = IndicationKind.ACUTE,
      status = IndicationStatus.RESOLVED,
      severity = Severity.MODERATE,
      diagnosedDateIso = today.minusMonths(5).toString(),
      endDateIso = today.minusMonths(5).plusDays(10).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condHeadache = Indication(
      id = UUID.randomUUID().toString(),
      name = "Tension Headache",
      icd10 = "G44.209",
      details = "Occasional episodic tension headaches and mild musculoskeletal pain.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.MANAGED,
      severity = Severity.MILD,
      diagnosedDateIso = today.minusYears(1).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    listOf(
      condDiabetes,
      condNeuropathy,
      condSinusitis,
      condVertigo,
      condAsthmaPrev,
      allergySulfa,
      allergyPollen,
      allergyPeanut,
      intoleranceNsaid,
      condHeadache,
      condStrep,
    ).forEach { db.upsertIndication(it) }

    // 3. Sample Medications
    // 3a. Generic Amox-Clav (Oral tablet antibiotic, 10-day partial course)
    val medAmoxClav = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Amoxicillin / Clavulanate Potassium",
      brandName = "Augmentin",
      manufacturer = "Sandoz / Teva",
      ndc11 = "00781-1852-20",
      strength = "875 mg / 125 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      minIntervalMinutes = 10 * 60, // 10 hours between twice-daily doses
      maxDailyDoses = 2.0,
      maxDailyAmount = 2.0,
      indicationIds = listOf(condSinusitis.id),
      indicationText = "Acute bacterial sinusitis — finish entire 10-day course",
      prescriberId = pcp.id,
      prescribedDateIso = today.minusDays(2).toString(),
      notes = "Take with food or a meal to minimize gastrointestinal discomfort.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3b. Tylenol / Acetaminophen (Oral OTC tablet, PRN)
    val medTylenol = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Acetaminophen",
      brandName = "Tylenol Extra Strength",
      manufacturer = "McNeil Consumer Healthcare",
      ndc11 = "50580-0496-50",
      strength = "500 mg",
      form = MedicationForm.TABLET,
      source = ItemSource.OTC,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      minIntervalMinutes = 360, // 6 hours minimum between doses
      maxDailyDoses = 6.0,
      maxDailyAmount = 6.0, // tablets: the limit is in the medication's default unit
      indicationIds = listOf(condHeadache.id),
      indicationText = "Tension headache or fever as needed",
      prescriberId = pcp.id,
      prescribedDateIso = today.minusMonths(2).toString(),
      notes = "Do not exceed 3,000 mg (6 tablets) in 24 hours. Avoid alcohol.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3c. Insulin Glargine (Injectable vial with U-100 syringe & site rotation, bedtime basal)
    val medInsulin = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Insulin Glargine (rDNA origin)",
      brandName = "Lantus",
      manufacturer = "Sanofi-Aventis",
      ndc11 = "00088-2220-33",
      strength = "100 units/mL (U-100)",
      form = MedicationForm.INJECTABLE_VIAL,
      defaultDoseAmount = 20.0,
      defaultDoseUnit = DoseUnit.UNITS,
      injectableDetails = InjectableDetails(
        route = InjectionRoute.SC,
        vialVolumeMl = 10.0,
        syringeType = SyringeType.INSULIN_U100,
        syringeVolumeMl = 0.5,
        // Insulin syringes have the needle attached: no separate draw needle.
        injectNeedleGauge = 31,
        injectNeedleLengthInches = 0.3125,
      ),
      siteMode = SiteMode.ROTATE,
      sites = listOf("Abdomen - Left", "Abdomen - Right", "Thigh - Left", "Thigh - Right"),
      minIntervalMinutes = 20 * 60, // 20 hours minimum between daily basal doses
      maxDailyDoses = 1.0,
      maxDailyAmount = 20.0,
      indicationIds = listOf(condDiabetes.id),
      indicationText = "Type 2 diabetes basal glycemic control at bedtime",
      prescriberId = endocrinologist.id,
      prescribedDateIso = today.minusMonths(6).toString(),
      notes = "Inject subcutaneously once daily at bedtime. Rotate injection sites across abdomen and thighs. Store unopened vials in refrigerator.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3d. Cyanocobalamin / Vitamin B12 (IM Vial with Luer-lock syringe + draw & inject needles + ISO hub colors)
    val medB12 = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Cyanocobalamin (Vitamin B12)",
      manufacturer = "American Regent",
      ndc11 = "00517-0031-25",
      strength = "1000 mcg/mL",
      form = MedicationForm.INJECTABLE_VIAL,
      defaultDoseAmount = 1000.0,
      defaultDoseUnit = DoseUnit.MCG,
      injectableDetails = InjectableDetails(
        route = InjectionRoute.IM,
        vialVolumeMl = 10.0,
        syringeType = SyringeType.LUER_LOCK,
        syringeVolumeMl = 3.0,
        drawNeedleGauge = 18,
        drawNeedleLengthInches = 1.0,
        injectNeedleGauge = 25,
        injectNeedleLengthInches = 1.0,
      ),
      siteMode = SiteMode.ROTATE,
      sites = Medication.PRESET_IM_THIGH_SITES,
      indicationIds = listOf(condNeuropathy.id),
      prescriberId = endocrinologist.id,
      prescribedDateIso = today.minusMonths(3).toString(),
      notes = "Draw 1 mL with 18G needle, switch to 25G needle for IM injection.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3e. Ketotifen Eye Drops (Drops treating environmental allergy with SiteMode.RECORD)
    val medEyeDrops = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Ketotifen Fumarate",
      brandName = "Zaditor",
      manufacturer = "Alcon",
      strength = "0.025%",
      form = MedicationForm.DROPS,
      source = ItemSource.OTC,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.DROP,
      siteMode = SiteMode.RECORD,
      sites = Medication.PRESET_EYE_SITES,
      indicationIds = listOf(allergyPollen.id),
      prescriberId = allergist.id,
      prescribedDateIso = today.minusMonths(1).toString(),
      notes = "Remove contact lenses before instilling drops.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3f. Isotretinoin: a titration. One capsule to start, two starting today, three planned.
    val schedIsotretinoinId = UUID.randomUUID().toString()
    val medIsotretinoin = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Isotretinoin",
      strength = "20 mg",
      form = MedicationForm.CAPSULE,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.CAPSULE,
      prescriberId = pcp.id,
      prescribedDateIso = today.minusDays(20).toString(),
      notes = "Take with a meal that contains fat.",
      adjustments = listOf(
        MedicationAdjustment(
          id = UUID.randomUUID().toString(),
          effectiveDateIso = today.toString(),
          note = "Increased at 3-week check",
          defaultDose = DoseAmount(2.0, DoseUnit.CAPSULE),
          scheduleDoses = listOf(ScheduleDose(schedIsotretinoinId, 2.0, DoseUnit.CAPSULE)),
        ),
        MedicationAdjustment(
          id = UUID.randomUUID().toString(),
          effectiveDateIso = today.plusDays(14).toString(),
          note = "Planned increase if tolerated",
          defaultDose = DoseAmount(3.0, DoseUnit.CAPSULE),
          scheduleDoses = listOf(ScheduleDose(schedIsotretinoinId, 3.0, DoseUnit.CAPSULE)),
        ),
      ),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3g. Prednisone (Finished course with a taken log within 90 days)
    val medPrednisone = Medication(
      id = UUID.randomUUID().toString(),
      formulaName = "Prednisone",
      strength = "10 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      prescriberId = pcp.id,
      prescribedDateIso = today.minusDays(18).toString(),
      notes = "Completed 5-day burst for acute airway inflammation.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    // 3h. Sample Supplements
    val suppVitaminC = Supplement(
      id = UUID.randomUUID().toString(),
      name = "Vitamin C",
      strength = "1000 mg",
      form = MedicationForm.TABLET,
      source = ItemSource.OTC,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      manufacturer = "Nature Made",
      ingredients = listOf(KeyIngredient("Vitamin C (Ascorbic Acid)", "1000 mg")),
      startedDateIso = today.minusMonths(2).toString(),
      notes = "Take with morning meal.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val suppFiber = Supplement(
      id = UUID.randomUUID().toString(),
      name = "Psyllium Husk Fiber",
      form = MedicationForm.POWDER,
      source = ItemSource.OTC,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TBSP,
      manufacturer = "Metamucil",
      ingredients = listOf(
        KeyIngredient("Psyllium Husk", "5.8 g"),
        KeyIngredient("Soluble Fiber", "3 g"),
      ),
      startedDateIso = today.minusMonths(1).toString(),
      notes = "Mix thoroughly into at least 8 oz of water and drink promptly.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val suppPreWorkout = Supplement(
      id = UUID.randomUUID().toString(),
      name = "Pre-Workout",
      form = MedicationForm.POWDER,
      source = ItemSource.OTC,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.SCOOP,
      manufacturer = "Optimum Nutrition",
      minIntervalMinutes = 12 * 60,
      maxDailyDoses = 1.0,
      ingredients = listOf(
        KeyIngredient("Caffeine", "175 mg"),
        KeyIngredient("Beta-Alanine", "1.5 g"),
        KeyIngredient("Creatine Monohydrate", "3 g"),
        KeyIngredient("L-Citrulline", "750 mg"),
      ),
      notes = "Take 20–30 minutes before training. Avoid within 6 hours of bedtime.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    listOf(
      medAmoxClav, medTylenol, medInsulin, medB12, medEyeDrops, medIsotretinoin, medPrednisone,
      suppVitaminC, suppFiber, suppPreWorkout,
    ).forEach {
      db.upsertMedication(it)
    }

    // 4. Sample Schedules
    val schedAmoxClavDaily = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusDays(2).toString(),
      endDateIso = today.plusDays(7).toString(), // 10-day antibiotic course
      startBucketId = TimeOfDayBucket.BUCKET_EVENING, // picked up and started in the evening
      endBucketId = TimeOfDayBucket.BUCKET_MORNING, // so the course ends with a morning dose
      medicationId = medAmoxClav.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING, TimeOfDayBucket.BUCKET_EVENING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedTylenolPrn = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      medicationId = medTylenol.id,
      frequencyType = ScheduleFrequencyType.AS_NEEDED,
      bucketIds = emptyList(),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedInsulinDaily = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusDays(2).toString(),
      medicationId = medInsulin.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_BEDTIME),
      doseAmount = 20.0,
      doseUnit = DoseUnit.UNITS,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedB12Days = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusMonths(3).toString(),
      medicationId = medB12.id,
      frequencyType = ScheduleFrequencyType.DAYS_OF_WEEK,
      daysOfWeek = setOf(1, 4), // Monday and Thursday
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1000.0,
      doseUnit = DoseUnit.MCG,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedEyeDropsDaily = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusDays(5).toString(),
      medicationId = medEyeDrops.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.DROP,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedIsotretinoin = MedicationSchedule(
      id = schedIsotretinoinId,
      startDateIso = today.minusDays(20).toString(),
      medicationId = medIsotretinoin.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_EVENING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.CAPSULE,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedPrednisoneFinished = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusDays(18).toString(),
      endDateIso = today.minusDays(14).toString(),
      medicationId = medPrednisone.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedVitaminCDaily = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusMonths(2).toString(),
      medicationId = suppVitaminC.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedFiberDaily = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      startDateIso = today.minusMonths(1).toString(),
      medicationId = suppFiber.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_EVENING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TBSP,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    val schedPreWorkoutPrn = MedicationSchedule(
      id = UUID.randomUUID().toString(),
      medicationId = suppPreWorkout.id,
      frequencyType = ScheduleFrequencyType.AS_NEEDED,
      bucketIds = emptyList(),
      doseAmount = 1.0,
      doseUnit = DoseUnit.SCOOP,
      enabled = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    listOf(
      schedAmoxClavDaily,
      schedTylenolPrn,
      schedInsulinDaily,
      schedB12Days,
      schedEyeDropsDaily,
      schedIsotretinoin,
      schedPrednisoneFinished,
      schedVitaminCDaily,
      schedFiberDaily,
      schedPreWorkoutPrn,
    ).forEach { db.upsertSchedule(it) }

    // 5. Prior Dose Logs:
    // - Yesterday bedtime insulin on "Abdomen - Left" so today suggests "Abdomen - Right"
    // - Yesterday morning eye drops on "Both Eyes" so SiteMode.RECORD suggests "Both Eyes"
    // - Prednisone taken 14 days ago so it appears in "Recently stopped" in Health Summary
    val yesterdayLog = DoseLog(
      id = UUID.randomUUID().toString(),
      medicationId = medInsulin.id,
      scheduleId = schedInsulinDaily.id,
      bucketId = TimeOfDayBucket.BUCKET_BEDTIME,
      scheduledDateIso = yesterdayIso,
      actionTimestampMillis = now - (24L * 3600L * 1000L),
      status = DoseStatus.TAKEN,
      amountTaken = 20.0,
      amountUnit = DoseUnit.UNITS,
      siteUsed = "Abdomen - Left",
      notes = "Taken at bedtime. No bruising.",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val yesterdayDropsLog = DoseLog(
      id = UUID.randomUUID().toString(),
      medicationId = medEyeDrops.id,
      scheduleId = schedEyeDropsDaily.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = yesterdayIso,
      actionTimestampMillis = now - (36L * 3600L * 1000L),
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.DROP,
      siteUsed = "Both Eyes",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val prednisoneLog = DoseLog(
      id = UUID.randomUUID().toString(),
      medicationId = medPrednisone.id,
      scheduleId = schedPrednisoneFinished.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = today.minusDays(14).toString(),
      actionTimestampMillis = now - (14L * 24L * 3600L * 1000L),
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val b12Day = (1L..6L).map { today.minusDays(it) }.first { it.dayOfWeek.value in setOf(1, 4) }
    val b12Log = DoseLog(
      id = UUID.randomUUID().toString(),
      medicationId = medB12.id,
      scheduleId = schedB12Days.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = b12Day.toString(),
      actionTimestampMillis = b12Day.atTime(8, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
      status = DoseStatus.TAKEN,
      amountTaken = 1000.0,
      amountUnit = DoseUnit.MCG,
      siteUsed = "Left Vastus Lateralis (Thigh)",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val vitaminCLog = DoseLog(
      id = UUID.randomUUID().toString(),
      medicationId = suppVitaminC.id,
      scheduleId = schedVitaminCDaily.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = yesterdayIso,
      actionTimestampMillis = now - (36L * 3600L * 1000L),
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    listOf(yesterdayLog, yesterdayDropsLog, b12Log, prednisoneLog, vitaminCLog).forEach { db.upsertDoseLog(it) }
  }

  companion object {
    private const val KEY_SEEDED_INITIAL = "seeded_initial_v1"
    private const val KEY_APP_SETTINGS = "app_settings_v1"
    private const val KEY_PRIMARY_CLAIM = "primary_claim_v1"
    private const val KEY_SYNC_DEVICES = "sync_devices_v1"

    @Volatile
    private var INSTANCE: RegimenRepository? = null

    fun getInstance(context: Context): RegimenRepository {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: RegimenRepository(context.applicationContext).also { INSTANCE = it }
      }
    }

    @VisibleForTesting
    internal fun resetForTests() {
      synchronized(this) {
        INSTANCE?.db?.close()
        INSTANCE = null
      }
    }
  }
}

