package com.exaclast.regimen.data

import android.content.Context
import com.exaclast.regimen.domain.ConditionTimeline
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.WindowDeletion
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionKind
import com.exaclast.regimen.model.ConditionStatus
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.sync.DeviceIdentity
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RegimenSnapshot(
  val settings: AppSettings = AppSettings(),
  val medications: List<Medication> = emptyList(),
  val providers: List<Provider> = emptyList(),
  val conditions: List<Condition> = emptyList(),
  val buckets: List<TimeOfDayBucket> = TimeOfDayBucket.defaultBuckets(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
) {
  /**
   * Providers and conditions are optional. Until the user chooses, they're shown only when
   * records exist: new installs start minimal, and nobody loses sight of data they entered.
   */
  val tracksProviders: Boolean
    get() = settings.trackProviders ?: providers.any { it.deletedAtEpochMillis == null }

  val tracksConditions: Boolean
    get() = settings.trackConditions ?: conditions.any { it.deletedAtEpochMillis == null }

  fun isArchived(condition: Condition, today: LocalDate = LocalDate.now()): Boolean =
    ConditionTimeline.isArchived(condition, today, medications, schedules)

  fun conditionEnd(condition: Condition): ConditionTimeline.End? =
    ConditionTimeline.end(condition, medications, schedules)
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
    val existingBuckets = db.getAllBuckets()
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

  private fun readSnapshot(): RegimenSnapshot = RegimenSnapshot(
    settings = loadSettings(),
    medications = db.getAllMedications(),
    providers = db.getAllProviders(),
    conditions = db.getAllConditions(),
    buckets = db.getAllBuckets().ifEmpty { TimeOfDayBucket.defaultBuckets() },
    schedules = db.getAllSchedules(),
    doseLogs = db.getAllDoseLogs(),
  )

  @Synchronized
  fun reloadSnapshot() {
    val nextSnapshot = readSnapshot()
    _snapshot.value = nextSnapshot
    DoseNotificationManager.refresh(appContext, nextSnapshot)
  }

  fun saveMedication(medication: Medication, schedulesForMedication: List<MedicationSchedule>? = null) {
    val now = System.currentTimeMillis()
    val normalized = medication.copy(
      ndc11 = Medication.normalizeNdc11(medication.ndc11),
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

  fun deleteMedication(medicationId: String) {
    val now = System.currentTimeMillis()
    val med = _snapshot.value.medications.find { it.id == medicationId } ?: return
    db.upsertMedication(med.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    _snapshot.value.schedules.filter { it.medicationId == medicationId }.forEach { sched ->
      db.upsertSchedule(sched.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  fun saveSchedule(schedule: MedicationSchedule) {
    val now = System.currentTimeMillis()
    db.upsertSchedule(schedule.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  fun deleteSchedule(scheduleId: String) {
    val now = System.currentTimeMillis()
    val sched = _snapshot.value.schedules.find { it.id == scheduleId } ?: return
    db.upsertSchedule(sched.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    reloadSnapshot()
  }

  fun saveProvider(provider: Provider) {
    val now = System.currentTimeMillis()
    db.upsertProvider(provider.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  fun deleteProvider(providerId: String) {
    val now = System.currentTimeMillis()
    val provider = _snapshot.value.providers.find { it.id == providerId } ?: return
    db.upsertProvider(provider.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    // Unlink medications it prescribed and conditions it was managing.
    _snapshot.value.medications.filter { it.prescriberId == providerId }.forEach { med ->
      db.upsertMedication(med.copy(prescriberId = null, updatedAtEpochMillis = now))
    }
    _snapshot.value.conditions.filter { providerId in it.providerIds }.forEach { condition ->
      db.upsertCondition(condition.copy(providerIds = condition.providerIds - providerId, updatedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  /** Sets which conditions [providerId] helps manage (edited from the provider's page). */
  fun setProviderConditions(providerId: String, conditionIds: Set<String>) {
    val now = System.currentTimeMillis()
    _snapshot.value.conditions.forEach { condition ->
      val linked = providerId in condition.providerIds
      val shouldLink = condition.id in conditionIds
      if (linked != shouldLink) {
        val ids = if (shouldLink) condition.providerIds + providerId else condition.providerIds - providerId
        db.upsertCondition(condition.copy(providerIds = ids, updatedAtEpochMillis = now))
      }
    }
    reloadSnapshot()
  }

  fun saveCondition(condition: Condition) {
    val now = System.currentTimeMillis()
    // Resolving without an end date ends it today, so it files under past conditions with a date.
    val ended = if (condition.status == ConditionStatus.RESOLVED && condition.endDateIso.isNullOrBlank()) {
      condition.copy(endDateIso = LocalDate.now().toString())
    } else {
      condition
    }
    db.upsertCondition(ended.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  fun deleteCondition(conditionId: String) {
    val now = System.currentTimeMillis()
    val condition = _snapshot.value.conditions.find { it.id == conditionId } ?: return
    db.upsertCondition(condition.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    // Unlink any medications pointing to this condition
    _snapshot.value.medications.filter { it.indicationConditionId == conditionId }.forEach { med ->
      db.upsertMedication(med.copy(indicationConditionId = null, updatedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  fun saveBucket(bucket: TimeOfDayBucket) {
    db.upsertBucket(bucket.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    reloadSnapshot()
  }

  /** Deletes a window, moving its doses to [moveToBucketId] (or the nearest window if none is given). */
  fun deleteBucket(bucketId: String, moveToBucketId: String? = null) {
    val buckets = db.getAllBuckets()
    val window = buckets.find { it.id == bucketId } ?: return
    val targetId = moveToBucketId ?: WindowDeletion.nearestWindow(window, buckets - window)?.id
    val plan = targetId?.let {
      WindowDeletion.plan(bucketId, it, db.getAllSchedules(), db.getAllDoseLogs(), System.currentTimeMillis())
    }
    db.inTransaction {
      plan?.scheduleChanges?.forEach { db.upsertSchedule(it.after) }
      plan?.movedLogs?.forEach { db.upsertDoseLog(it) }
      db.deleteBucket(bucketId)
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

  fun resetBucketsToDefault() {
    val current = db.getAllBuckets()
    val usedWindowIds = db.getAllSchedules().flatMapTo(HashSet()) { it.bucketIds }
    val restored = TimeOfDayBucket.windowsAfterRestoringDefaults(current, usedWindowIds)
    val keepIds = restored.mapTo(HashSet()) { it.id }
    val removed = current.filter { it.id !in keepIds }
    db.inTransaction {
      removed.forEach { db.deleteBucket(it.id) }
      restored.forEach { db.upsertBucket(it) }
    }
    removed.forEach { DoseNotificationManager.cancelBucketAlarms(appContext, it.id) }
    reloadSnapshot()
  }

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
  ): DoseLog? {
    val state = _snapshot.value
    val med = state.medications.find { it.id == medicationId } ?: return null
    val schedule = scheduleId?.let { id -> state.schedules.find { it.id == id } }
    val resolvedAmount = amountTaken ?: schedule?.doseAmount ?: med.defaultDoseAmount
    val resolvedUnit = amountUnit ?: schedule?.doseUnit ?: med.defaultDoseUnit
    val resolvedSite = if (status == DoseStatus.TAKEN) {
      siteUsed ?: DoseEngine.computeNextSite(med, state.doseLogs)
    } else {
      siteUsed
    }

    val now = System.currentTimeMillis()
    val log = DoseLog(
      id = existingLogId ?: java.util.UUID.randomUUID().toString(),
      medicationId = medicationId,
      scheduleId = scheduleId,
      bucketId = bucketId,
      scheduledDateIso = scheduledDateIso,
      actionTimestampMillis = actionTimestampMillis ?: now,
      status = status,
      snoozedUntilMillis = snoozedUntilMillis,
      amountTaken = resolvedAmount,
      amountUnit = resolvedUnit,
      siteUsed = resolvedSite,
      notes = notes,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now,
    )
    db.upsertDoseLog(log)
    if (scheduleId != null && bucketId != null) {
      val itemKey = "${scheduleId}_${bucketId}_$scheduledDateIso"
      DoseNotificationManager.cancelNotificationForItem(appContext, itemKey)
      // A new snooze is armed by the refresh in reloadSnapshot.
      if (status != DoseStatus.SNOOZED) {
        DoseNotificationManager.cancelSnoozeAlarm(appContext, itemKey, bucketId)
      }
    }
    reloadSnapshot()
    return log
  }

  fun deleteDoseLog(logId: String) {
    val now = System.currentTimeMillis()
    val log = _snapshot.value.doseLogs.find { it.id == logId } ?: return
    db.upsertDoseLog(log.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    itemKeyFor(log)?.let { key -> DoseNotificationManager.cancelSnoozeAlarm(appContext, key, log.bucketId) }
    reloadSnapshot()
  }

  /**
   * Restores [log] exactly as it was (used by Undo): un-deletes it, or puts back the state a newer action
   * overwrote. A restored future snooze is re-armed by the refresh in reloadSnapshot.
   */
  fun restoreDoseLog(log: DoseLog) {
    val now = System.currentTimeMillis()
    val restored = log.copy(deletedAtEpochMillis = null, updatedAtEpochMillis = now)
    db.upsertDoseLog(restored)
    val stillSnoozed = restored.status == DoseStatus.SNOOZED && (restored.snoozedUntilMillis ?: 0L) > now
    if (!stillSnoozed) {
      itemKeyFor(restored)?.let { key -> DoseNotificationManager.cancelSnoozeAlarm(appContext, key, restored.bucketId) }
    }
    reloadSnapshot()
  }

  /** Un-deletes a schedule (used by Undo). */
  fun restoreSchedule(schedule: MedicationSchedule) {
    db.upsertSchedule(schedule.copy(deletedAtEpochMillis = null, updatedAtEpochMillis = System.currentTimeMillis()))
    reloadSnapshot()
  }

  private fun itemKeyFor(log: DoseLog): String? {
    val scheduleId = log.scheduleId ?: return null
    val bucketId = log.bucketId ?: return null
    return "${scheduleId}_${bucketId}_${log.scheduledDateIso}"
  }

  /**
   * Everything on this device as a v3 bundle (the input to export and sync), including deletion
   * tombstones so a device that never saw a record still learns it was deleted.
   */
  @Synchronized
  fun currentBundle(): RegimenBackupBundle {
    val settings = loadSettings()
    return RegimenBackupBundle(
      exportedAtEpochMillis = System.currentTimeMillis(),
      sourceDeviceId = deviceIdentity.deviceId,
      settings = settings,
      sharedSettings = settings.shared(),
      devices = listOf(deviceIdentity.asSyncDevice()),
      medications = db.getAllMedications(includeDeleted = true),
      providers = db.getAllProviders(includeDeleted = true),
      conditions = db.getAllConditions(includeDeleted = true),
      buckets = db.getAllBuckets().ifEmpty { TimeOfDayBucket.defaultBuckets() },
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
    prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(settings)).apply()

    db.inTransaction {
      if (replace) db.clearUserRecords()
      val buckets = bundle.buckets.ifEmpty { TimeOfDayBucket.defaultBuckets() }
      val keepBucketIds = buckets.mapTo(HashSet()) { it.id }
      db.getAllBuckets().filter { it.id !in keepBucketIds }.forEach {
        db.deleteBucket(it.id)
        DoseNotificationManager.cancelBucketAlarms(appContext, it.id)
      }
      buckets.forEach { db.upsertBucket(it) }
      bundle.providers.forEach { db.upsertProvider(it) }
      bundle.conditions.forEach { db.upsertCondition(it) }
      bundle.medications.forEach { db.upsertMedication(it) }
      bundle.schedules.forEach { db.upsertSchedule(it) }
      bundle.doseLogs.forEach { db.upsertDoseLog(it) }
    }
    reloadSnapshot()
  }

  @Synchronized
  fun clearAllUserData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
    db.clearUserRecords()
    reloadSnapshot()
  }

  @Synchronized
  fun resetToSampleData() {
    DoseNotificationManager.cancelAllNotifications(appContext)
    db.clearUserRecords()
    seedInitialSampleData()
    reloadSnapshot()
  }

  private fun seedInitialSampleData() {
    val today = LocalDate.now()
    val todayIso = today.toString()
    val yesterdayIso = today.minusDays(1).toString()
    val now = System.currentTimeMillis()

    // 1. Sample Providers
    val pcp = Provider(
      id = "provider_pcp_1",
      name = "Dr. Elena Rostova, MD",
      specialty = "Family Medicine & Primary Care",
      practice = "Metro Health Family Practice",
      phone = "555-014-8820",
      email = "erostova@metrohealth.example.org",
      isPcp = true,
      careStartDateIso = today.minusYears(1).toString(),
      notes = "Annual wellness and primary care",
    )
    val endocrinologist = Provider(
      id = "provider_endo_1",
      name = "Dr. Marcus Vance, MD",
      specialty = "Endocrinology & Diabetes Care",
      practice = "Valley Endocrine Specialists",
      phone = "555-019-3341",
      email = "mvance@valleyendocrine.example.org",
      isPcp = false,
      careStartDateIso = today.minusMonths(6).toString(),
      notes = "Quarterly diabetic management review",
    )
    db.upsertProvider(pcp)
    db.upsertProvider(endocrinologist)

    // 2. Sample Conditions
    val condDiabetes = Condition(
      id = "cond_diabetes",
      name = "Type 2 Diabetes Mellitus",
      details = "Glycemic control, fasting blood glucose monitoring, and basal insulin regimen.",
      kind = ConditionKind.CHRONIC,
      status = ConditionStatus.MANAGED,
      diagnosedDateIso = today.minusMonths(6).toString(),
      providerIds = listOf("provider_endo_1", "provider_pcp_1"),
    )
    val condSinusitis = Condition(
      id = "cond_sinusitis",
      name = "Acute Bacterial Sinusitis",
      details = "10-day oral antibiotic course for acute bacterial sinus infection.",
      kind = ConditionKind.ACUTE,
      status = ConditionStatus.ACTIVE,
      diagnosedDateIso = today.minusDays(2).toString(),
      providerIds = listOf("provider_pcp_1"),
    )
    // A past episode, to show archived conditions.
    val condStrep = Condition(
      id = "cond_strep_past",
      name = "Strep Throat",
      details = "Treated with a 10-day amoxicillin course.",
      kind = ConditionKind.ACUTE,
      status = ConditionStatus.RESOLVED,
      diagnosedDateIso = today.minusMonths(5).toString(),
      endDateIso = today.minusMonths(5).plusDays(10).toString(),
      providerIds = listOf("provider_pcp_1"),
    )
    val condHeadache = Condition(
      id = "cond_headache",
      name = "Tension Headache",
      details = "Occasional episodic tension headaches and mild musculoskeletal pain.",
      kind = ConditionKind.CHRONIC,
      status = ConditionStatus.MANAGED,
      diagnosedDateIso = today.minusYears(1).toString(),
      providerIds = listOf("provider_pcp_1"),
    )
    db.upsertCondition(condDiabetes)
    db.upsertCondition(condSinusitis)
    db.upsertCondition(condHeadache)
    db.upsertCondition(condStrep)

    // 3. Sample Medications
    // 3a. Generic Amox-Clav (Oral tablet antibiotic, twice daily)
    val medAmoxClav = Medication(
      id = "med_amox_clav",
      formulaName = "Amoxicillin / Clavulanate Potassium",
      brandName = "Augmentin",
      manufacturer = "Sandoz / Teva",
      ndc11 = "00781-1852-20",
      strength = "875 mg / 125 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      siteRotationEnabled = false,
      minIntervalMinutes = 10 * 60, // 10 hours between twice-daily doses
      maxDailyDoses = 2.0,
      maxDailyAmount = 2.0,
      indicationConditionId = condSinusitis.id,
      indicationText = "Acute bacterial sinusitis — finish entire 10-day course",
      prescriberId = pcp.id,
      prescribedDateIso = today.minusDays(2).toString(),
      notes = "Take with food or a meal to minimize gastrointestinal discomfort.",
    )

    // 3b. Tylenol / Acetaminophen (Oral OTC tablet, PRN)
    val medTylenol = Medication(
      id = "med_tylenol",
      formulaName = "Acetaminophen",
      brandName = "Tylenol Extra Strength",
      manufacturer = "McNeil Consumer Healthcare",
      ndc11 = "50580-0496-50",
      strength = "500 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      siteRotationEnabled = false,
      minIntervalMinutes = 360, // 6 hours minimum between doses
      maxDailyDoses = 6.0,
      maxDailyAmount = 6.0, // tablets: the limit is in the medication's default unit
      indicationConditionId = condHeadache.id,
      indicationText = "Tension headache or fever as needed",
      prescriberId = pcp.id,
      prescribedDateIso = today.minusMonths(2).toString(),
      notes = "Do not exceed 3,000 mg (6 tablets) in 24 hours. Avoid alcohol.",
    )

    // 3c. Insulin Glargine (Injectable vial with syringe & site rotation, bedtime basal)
    val medInsulin = Medication(
      id = "med_insulin_glargine",
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
        concentrationMgPerMl = 100.0,
        vialVolumeMl = 10.0,
        syringeVolumeMl = 0.5,
        syringeDescription = "0.5 mL U-100 Insulin Syringe (31G 5/16\")",
        drawNeedleGauge = 31,
        drawNeedleLengthInches = 0.3125,
        injectNeedleGauge = 31,
        injectNeedleLengthInches = 0.3125,
      ),
      siteRotationEnabled = true,
      rotationSites = listOf("Abdomen - Left", "Abdomen - Right", "Thigh - Left", "Thigh - Right"),
      minIntervalMinutes = 20 * 60, // 20 hours minimum between daily basal doses
      maxDailyDoses = 1.0,
      maxDailyAmount = 20.0,
      indicationConditionId = condDiabetes.id,
      indicationText = "Type 2 diabetes basal glycemic control at bedtime",
      prescriberId = endocrinologist.id,
      prescribedDateIso = today.minusMonths(6).toString(),
      notes = "Inject subcutaneously once daily at bedtime. Rotate injection sites across abdomen and thighs. Store unopened vials in refrigerator.",
    )

    db.upsertMedication(medAmoxClav)
    db.upsertMedication(medTylenol)
    db.upsertMedication(medInsulin)

    // 4. Sample Schedules
    val schedAmoxClavDaily = MedicationSchedule(
      id = "sched_amox_clav",
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
    )

    val schedTylenolPrn = MedicationSchedule(
      id = "sched_tylenol_prn",
      medicationId = medTylenol.id,
      frequencyType = ScheduleFrequencyType.AS_NEEDED,
      bucketIds = emptyList(),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
    )

    val schedInsulinDaily = MedicationSchedule(
      id = "sched_insulin_bedtime",
      startDateIso = today.minusDays(2).toString(),
      medicationId = medInsulin.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_BEDTIME),
      doseAmount = 20.0,
      doseUnit = DoseUnit.UNITS,
      enabled = true,
    )

    db.upsertSchedule(schedAmoxClavDaily)
    db.upsertSchedule(schedTylenolPrn)
    db.upsertSchedule(schedInsulinDaily)

    // 5. Prior Dose Log: Yesterday evening's insulin dose logged on "Abdomen - Left" so today suggests "Abdomen - Right"!
    val yesterdayLog = DoseLog(
      id = "log_insulin_yesterday",
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
    )
    db.upsertDoseLog(yesterdayLog)
  }

  companion object {
    private const val KEY_SEEDED_INITIAL = "seeded_initial_v1"
    private const val KEY_APP_SETTINGS = "app_settings_v1"

    @Volatile
    private var INSTANCE: RegimenRepository? = null

    fun getInstance(context: Context): RegimenRepository {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: RegimenRepository(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}

