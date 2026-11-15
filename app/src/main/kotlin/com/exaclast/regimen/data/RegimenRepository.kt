package com.exaclast.regimen.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.WindowDeletion
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.SiteMode
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
  val medications: List<Medication> = emptyList(),
  val deletedMedications: List<Medication> = emptyList(),
  val providers: List<Provider> = emptyList(),
  val indications: List<Indication> = emptyList(),
  val buckets: List<TimeOfDayBucket> = TimeOfDayBucket.defaultBuckets(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
) {
  val allMedications: List<Medication>
    get() = medications + deletedMedications

  fun medication(id: String): Medication? =
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
  fun saveMedication(medication: Medication, schedulesForMedication: List<MedicationSchedule>? = null) {
    val now = System.currentTimeMillis()
    val normalized = medication.copy(
      ndc11 = Medication.normalizeNdc11(medication.ndc11),
      // A blank NDC on an adjustment means "cleared" and must stay blank, not become null (carry over).
      adjustments = medication.adjustments.map { adjustment ->
        adjustment.ndc11?.takeIf { it.isNotBlank() }?.let { adjustment.copy(ndc11 = Medication.normalizeNdc11(it)) } ?: adjustment
      },
      updatedAtEpochMillis = now,
    )
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
    db.upsertMedication(med.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
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
  fun saveScheduleEdit(medication: Medication?, schedules: List<MedicationSchedule>) {
    if (medication == null && schedules.isEmpty()) return
    val now = System.currentTimeMillis()
    db.inTransaction {
      medication?.let { db.upsertMedication(it.copy(updatedAtEpochMillis = now)) }
      schedules.forEach { db.upsertSchedule(it.copy(updatedAtEpochMillis = now)) }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun deleteSchedule(scheduleId: String) {
    val now = System.currentTimeMillis()
    val sched = _snapshot.value.schedules.find { it.id == scheduleId } ?: return
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
      db.upsertMedication(med.copy(prescriberId = null, updatedAtEpochMillis = now))
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
    // Resolving without an end date ends it today, so it files under past indications with a date.
    val ended = if (indication.status == IndicationStatus.RESOLVED && indication.endDateIso.isNullOrBlank()) {
      indication.copy(endDateIso = LocalDate.now().toString())
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
      db.upsertMedication(med.copy(indicationIds = med.indicationIds - indicationId, updatedAtEpochMillis = now))
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
        db.upsertMedication(med.copy(indicationIds = ids, updatedAtEpochMillis = now))
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
    val now = System.currentTimeMillis()

    val created = requests.mapNotNull { req ->
      val stored = medMap[req.medicationId] ?: return@mapNotNull null
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

      val log = DoseLog(
        id = req.existingLogId ?: UUID.randomUUID().toString(),
        medicationId = req.medicationId,
        scheduleId = req.scheduleId,
        bucketId = req.bucketId,
        scheduledDateIso = req.scheduledDateIso,
        actionTimestampMillis = req.actionTimestampMillis ?: now,
        status = req.status,
        snoozedUntilMillis = req.snoozedUntilMillis,
        amountTaken = resolvedAmount,
        amountUnit = resolvedUnit,
        siteUsed = resolvedSite,
        notes = req.notes,
        createdAtEpochMillis = now,
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
    return RegimenBackupBundle(
      exportedAtEpochMillis = System.currentTimeMillis(),
      sourceDeviceId = deviceIdentity.deviceId,
      settings = settings,
      sharedSettings = settings.shared(),
      devices = devices,
      primary = loadPrimaryClaim(),
      medications = db.getAllMedications(includeDeleted = true),
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
    removedBucketIds.forEach { DoseNotificationManager.cancelBucketAlarms(appContext, it) }
    reloadSnapshot()
  }

  /** Soft-deletes every live user record so existing tombstones stay intact for sync. */
  private fun softDeleteAllUserRecords(now: Long) {
    db.inTransaction {
      db.getAllDoseLogs().forEach { db.upsertDoseLog(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllSchedules().forEach { db.upsertSchedule(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllMedications().forEach { db.upsertMedication(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllProviders().forEach { db.upsertProvider(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
      db.getAllIndications().forEach { db.upsertIndication(it.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now)) }
    }
  }

  @Synchronized
  fun clearAllUserData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
    softDeleteAllUserRecords(System.currentTimeMillis())
    reloadSnapshot(notifyAutoBackup = false)
  }

  @Synchronized
  fun resetToSampleData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
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

    // 1. Sample Providers
    val pcp = Provider(
      id = UUID.randomUUID().toString(),
      name = "Dr. Elena Rostova, MD",
      specialty = "Family Medicine & Primary Care",
      practice = "Metro Health Family Practice",
      phone = "555-014-8820",
      email = "erostova@metrohealth.example.org",
      isPcp = true,
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
      careStartDateIso = today.minusMonths(6).toString(),
      notes = "Quarterly diabetic management review",
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    db.upsertProvider(pcp)
    db.upsertProvider(endocrinologist)

    // 2. Sample Indications
    val condDiabetes = Indication(
      id = UUID.randomUUID().toString(),
      name = "Type 2 Diabetes Mellitus",
      details = "Glycemic control, fasting blood glucose monitoring, and basal insulin regimen.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.MANAGED,
      diagnosedDateIso = today.minusMonths(6).toString(),
      providerIds = listOf(endocrinologist.id, pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condSinusitis = Indication(
      id = UUID.randomUUID().toString(),
      name = "Acute Bacterial Sinusitis",
      details = "10-day oral antibiotic course for acute bacterial sinus infection.",
      kind = IndicationKind.ACUTE,
      status = IndicationStatus.ACTIVE,
      diagnosedDateIso = today.minusDays(2).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    // A past episode, to show archived indications.
    val condStrep = Indication(
      id = UUID.randomUUID().toString(),
      name = "Strep Throat",
      details = "Treated with a 10-day amoxicillin course.",
      kind = IndicationKind.ACUTE,
      status = IndicationStatus.RESOLVED,
      diagnosedDateIso = today.minusMonths(5).toString(),
      endDateIso = today.minusMonths(5).plusDays(10).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    val condHeadache = Indication(
      id = UUID.randomUUID().toString(),
      name = "Tension Headache",
      details = "Occasional episodic tension headaches and mild musculoskeletal pain.",
      kind = IndicationKind.CHRONIC,
      status = IndicationStatus.MANAGED,
      diagnosedDateIso = today.minusYears(1).toString(),
      providerIds = listOf(pcp.id),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    db.upsertIndication(condDiabetes)
    db.upsertIndication(condSinusitis)
    db.upsertIndication(condHeadache)
    db.upsertIndication(condStrep)

    // 3. Sample Medications
    // 3a. Generic Amox-Clav (Oral tablet antibiotic, twice daily)
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

    // 3c. Insulin Glargine (Injectable vial with syringe & site rotation, bedtime basal)
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

    // 3d. Isotretinoin: a titration. One capsule to start, two from a check-up, three planned.
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
          effectiveDateIso = today.minusDays(6).toString(),
          note = "Increased at 2-week check",
          scheduleDoses = listOf(ScheduleDose(schedIsotretinoinId, 2.0, DoseUnit.CAPSULE)),
        ),
        MedicationAdjustment(
          id = UUID.randomUUID().toString(),
          effectiveDateIso = today.plusDays(10).toString(),
          note = "Planned increase if tolerated",
          scheduleDoses = listOf(ScheduleDose(schedIsotretinoinId, 3.0, DoseUnit.CAPSULE)),
        ),
      ),
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )

    db.upsertMedication(medAmoxClav)
    db.upsertMedication(medTylenol)
    db.upsertMedication(medInsulin)
    db.upsertMedication(medIsotretinoin)

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

    db.upsertSchedule(schedAmoxClavDaily)
    db.upsertSchedule(schedTylenolPrn)
    db.upsertSchedule(schedInsulinDaily)
    db.upsertSchedule(schedIsotretinoin)

    // 5. Prior Dose Log: Yesterday evening's insulin dose logged on "Abdomen - Left" so today suggests "Abdomen - Right"!
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
    db.upsertDoseLog(yesterdayLog)
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

