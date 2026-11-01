package com.exaclast.regimen.data

import android.content.Context
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionStatus
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RegimenSnapshot(
  val settings: AppSettings = AppSettings(),
  val medications: List<Medication> = emptyList(),
  val prescribers: List<Prescriber> = emptyList(),
  val conditions: List<Condition> = emptyList(),
  val buckets: List<TimeOfDayBucket> = TimeOfDayBucket.defaultBuckets(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
)

/**
 * Singleton offline-first repository backed by SQLite, architected with soft-delete tombstones,
 * UUIDs, and JSON export/import so future Cloud Sync providers can plug in seamlessly.
 */
class RegimenRepository private constructor(context: Context) {

  private val appContext = context.applicationContext
  private val db = RegimenDatabase(appContext)
  private val prefs = appContext.getSharedPreferences("regimen_prefs", Context.MODE_PRIVATE)
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
      seedInitialSampleData()
      prefs.edit().putBoolean(KEY_SEEDED_INITIAL, true).apply()
    }
    reloadSnapshot()
  }

  private fun loadSettings(): AppSettings {
    val raw = prefs.getString(KEY_APP_SETTINGS, null) ?: return AppSettings()
    return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
  }

  fun saveSettings(settings: AppSettings) {
    prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(settings)).apply()
    reloadSnapshot()
  }

  @Synchronized
  fun reloadSnapshot() {
    val loadedSettings = loadSettings()
    val nextSnapshot = RegimenSnapshot(
      settings = loadedSettings,
      medications = db.getAllMedications(),
      prescribers = db.getAllPrescribers(),
      conditions = db.getAllConditions(),
      buckets = db.getAllBuckets().ifEmpty { TimeOfDayBucket.defaultBuckets() },
      schedules = db.getAllSchedules(),
      doseLogs = db.getAllDoseLogs(),
    )
    _snapshot.value = nextSnapshot
    DoseNotificationManager.scheduleDailyBucketAlarms(
      context = appContext,
      buckets = nextSnapshot.buckets,
      settings = loadedSettings,
    )
    DoseNotificationManager.syncActiveWindowNotifications(appContext, nextSnapshot)
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

  fun savePrescriber(prescriber: Prescriber) {
    val now = System.currentTimeMillis()
    db.upsertPrescriber(prescriber.copy(updatedAtEpochMillis = now))
    reloadSnapshot()
  }

  fun deletePrescriber(prescriberId: String) {
    val now = System.currentTimeMillis()
    val prescriber = _snapshot.value.prescribers.find { it.id == prescriberId } ?: return
    db.upsertPrescriber(prescriber.copy(updatedAtEpochMillis = now, deletedAtEpochMillis = now))
    // Unlink any medications pointing to this prescriber
    _snapshot.value.medications.filter { it.prescriberId == prescriberId }.forEach { med ->
      db.upsertMedication(med.copy(prescriberId = null, updatedAtEpochMillis = now))
    }
    reloadSnapshot()
  }

  fun saveCondition(condition: Condition) {
    val now = System.currentTimeMillis()
    db.upsertCondition(condition.copy(updatedAtEpochMillis = now))
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
    db.upsertBucket(bucket)
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
      actionTimestampMillis = now,
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
      if (status == DoseStatus.SNOOZED && snoozedUntilMillis != null) {
        DoseNotificationManager.scheduleSnoozeAlarm(appContext, itemKey, bucketId, snoozedUntilMillis)
      } else {
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
    reloadSnapshot()
  }

  fun exportBackupJson(): String {
    val s = _snapshot.value
    val bundle = RegimenBackupBundle(
      version = 2,
      exportedAtEpochMillis = System.currentTimeMillis(),
      settings = s.settings,
      medications = s.medications,
      prescribers = s.prescribers,
      conditions = s.conditions,
      buckets = s.buckets,
      schedules = s.schedules,
      doseLogs = s.doseLogs,
    )
    return json.encodeToString(bundle)
  }

  fun importBackupJson(rawJson: String): Result<Int> {
    return runCatching {
      val bundle = json.decodeFromString<RegimenBackupBundle>(rawJson)
      prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(bundle.settings)).apply()
      bundle.buckets.forEach { db.upsertBucket(it) }
      bundle.prescribers.forEach { db.upsertPrescriber(it) }
      bundle.conditions.forEach { db.upsertCondition(it) }
      bundle.medications.forEach { db.upsertMedication(it) }
      bundle.schedules.forEach { db.upsertSchedule(it) }
      bundle.doseLogs.forEach { db.upsertDoseLog(it) }
      reloadSnapshot()
      bundle.medications.size
    }
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
    val lastWeekIso = today.minusDays(7).toString()
    val now = System.currentTimeMillis()

    // 1. Sample Prescribers
    val pcp = Prescriber(
      id = "prescriber_pcp_1",
      name = "Dr. Elena Rostova, MD",
      specialty = "Internal Medicine / Primary Care",
      practice = "Metro Health Primary Care",
      phone = "555-014-8820",
      email = "erostova@metrohealth.example.org",
      isPcp = true,
      careStartDateIso = "2023-04-15",
      notes = "Annual wellness & ongoing primary care management",
    )
    val psychiatrist = Prescriber(
      id = "prescriber_psych_1",
      name = "Dr. Marcus Vance, DO",
      specialty = "Psychiatry & Behavioral Neurobiology",
      practice = "Clarity Focus Clinic",
      phone = "555-019-3341",
      email = "mvance@clarityfocus.example.org",
      isPcp = false,
      careStartDateIso = "2024-01-10",
      notes = "Quarterly ADHD medication review",
    )
    db.upsertPrescriber(pcp)
    db.upsertPrescriber(psychiatrist)

    // 2. Sample Conditions
    val condAdhd = Condition(
      id = "cond_adhd",
      name = "ADHD (Combined Presentation)",
      details = "Executive function, working memory, and task initiation support.",
      status = ConditionStatus.MANAGED,
      diagnosedDateIso = "2024-01-10",
    )
    val condEndo = Condition(
      id = "cond_hrt",
      name = "Endocrine / Hormone Replacement Therapy",
      details = "Weekly intramuscular depot maintenance protocol with left/right site rotation.",
      status = ConditionStatus.MANAGED,
      diagnosedDateIso = "2023-06-01",
    )
    db.upsertCondition(condAdhd)
    db.upsertCondition(condEndo)

    // 3. Sample Medications
    val medInjectableIm = Medication(
      id = "med_cypionate_im",
      formulaName = "Testosterone Cypionate",
      brandName = "Depo-Testosterone",
      manufacturer = "Pfizer / Hikma",
      ndc11 = "00009-0085-10",
      strength = "200 mg/mL",
      form = MedicationForm.INJECTABLE_VIAL,
      defaultDoseAmount = 100.0,
      defaultDoseUnit = DoseUnit.MG,
      injectableDetails = InjectableDetails(
        route = InjectionRoute.IM,
        concentrationMgPerMl = 200.0,
        vialVolumeMl = 10.0,
        syringeVolumeMl = 1.0,
        syringeDescription = "1 mL Luer-Lok Tuberculin",
        drawNeedleGauge = 18,
        drawNeedleLengthInches = 1.0,
        injectNeedleGauge = 25,
        injectNeedleLengthInches = 1.0,
      ),
      siteRotationEnabled = true,
      rotationSites = listOf("Left Ventrogluteal", "Right Ventrogluteal"),
      minIntervalMinutes = 5 * 24 * 60, // 5 days minimum safety interval
      maxDailyDoses = 1.0,
      maxDailyAmount = 100.0,
      indicationConditionId = condEndo.id,
      indicationText = "Weekly IM maintenance — switches sides every week",
      prescriberId = pcp.id,
      prescribedDateIso = "2025-11-01",
      notes = "Warm vial in hand before drawing with 18G needle; switch to 25G 1\" needle for IM injection.",
    )

    val medVyvanse = Medication(
      id = "med_vyvanse",
      formulaName = "Lisdexamfetamine Dimesylate",
      brandName = "Vyvanse",
      manufacturer = "Takeda Pharmaceuticals",
      ndc11 = "59417-0104-10",
      strength = "40 mg",
      form = MedicationForm.CAPSULE,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.CAPSULE,
      siteRotationEnabled = false,
      minIntervalMinutes = 18 * 60, // 18 hours between morning doses
      maxDailyDoses = 1.0,
      maxDailyAmount = 1.0,
      indicationConditionId = condAdhd.id,
      indicationText = "Primary daily ADHD symptom management",
      prescriberId = psychiatrist.id,
      prescribedDateIso = "2026-01-15",
      notes = "Take in the morning with protein breakfast.",
    )

    val medDexPrn = Medication(
      id = "med_dex_prn",
      formulaName = "Dextroamphetamine Sulfate",
      brandName = "Zenzedi",
      manufacturer = "Azurity",
      ndc11 = "24338-0853-13",
      strength = "5 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      siteRotationEnabled = false,
      minIntervalMinutes = 240, // 4 hours minimum between doses
      maxDailyDoses = 2.0, // Max 2 booster tablets per day
      maxDailyAmount = 2.0,
      indicationConditionId = condAdhd.id,
      indicationText = "Afternoon executive function booster as needed",
      prescriberId = psychiatrist.id,
      prescribedDateIso = "2026-01-15",
      notes = "Avoid taking after 4:00 PM to prevent insomnia.",
    )

    db.upsertMedication(medInjectableIm)
    db.upsertMedication(medVyvanse)
    db.upsertMedication(medDexPrn)

    // 4. Sample Schedules
    val schedInjectableWeekly = MedicationSchedule(
      id = "sched_im_weekly",
      medicationId = medInjectableIm.id,
      frequencyType = ScheduleFrequencyType.EVERY_N_DAYS,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      intervalDays = 7,
      anchorDateIso = lastWeekIso, // Due today!
      doseAmount = 100.0,
      doseUnit = DoseUnit.MG,
      enabled = true,
    )

    val schedVyvanseDaily = MedicationSchedule(
      id = "sched_vyvanse_daily",
      medicationId = medVyvanse.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.CAPSULE,
      enabled = true,
    )

    val schedDexAsNeeded = MedicationSchedule(
      id = "sched_dex_prn",
      medicationId = medDexPrn.id,
      frequencyType = ScheduleFrequencyType.AS_NEEDED,
      bucketIds = emptyList(),
      doseAmount = 1.0,
      doseUnit = DoseUnit.TABLET,
      enabled = true,
    )

    db.upsertSchedule(schedInjectableWeekly)
    db.upsertSchedule(schedVyvanseDaily)
    db.upsertSchedule(schedDexAsNeeded)

    // 5. Log last week's IM injection on Left Ventrogluteal so today's auto-rotation suggests Right Ventrogluteal!
    val lastWeekLog = DoseLog(
      id = "log_im_last_week",
      medicationId = medInjectableIm.id,
      scheduleId = schedInjectableWeekly.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = lastWeekIso,
      actionTimestampMillis = now - (7L * 24L * 3600L * 1000L),
      status = DoseStatus.TAKEN,
      amountTaken = 100.0,
      amountUnit = DoseUnit.MG,
      siteUsed = "Left Ventrogluteal",
      notes = "Smooth draw and injection on left side.",
    )
    db.upsertDoseLog(lastWeekLog)
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

