package com.exaclast.regimen.model

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class MedicationForm(val displayName: String, val supportsSiteRotation: Boolean) {
  TABLET("Tablet", false),
  CAPSULE("Capsule", false),
  INJECTABLE_VIAL("Injectable (Vial)", true),
  INJECTABLE_PEN("Injectable (Pen / Auto-injector)", true),
  TOPICAL("Topical (Gel / Cream / Patch)", true),
  LIQUID("Oral Liquid / Solution", false),
  INHALER("Inhaler / Nasal Spray", false),
  DROPS("Drops (Ophthalmic / Otic)", false),
  OTHER("Other", false);

  val isInjectable: Boolean
    get() = this == INJECTABLE_VIAL || this == INJECTABLE_PEN
}

@Serializable
enum class InjectionRoute(val code: String, val displayName: String) {
  IM("IM", "Intramuscular (IM)"),
  SC("SC", "Subcutaneous (SC)"),
  INTRADERMAL("ID", "Intradermal (ID)"),
  IV("IV", "Intravenous (IV)"),
}

@Serializable
enum class DoseUnit(val symbol: String, val displayName: String) {
  MG("mg", "Milligrams (mg)"),
  MCG("mcg", "Micrograms (mcg)"),
  ML("mL", "Milliliters (mL)"),
  UNITS("units", "Syringe Units (U-100)"),
  IU("IU", "International Units (IU)"),
  TABLET("tab", "Tablet(s)"),
  CAPSULE("cap", "Capsule(s)"),
  APPLICATION("app", "Application / Pump(s)"),
  PUFF("puff", "Puff(s) / Spray(s)"),
  DROP("drop", "Drop(s)"),
}

@Serializable
data class InjectableDetails(
  val route: InjectionRoute = InjectionRoute.IM,
  val concentrationMgPerMl: Double? = null,
  val vialVolumeMl: Double? = null,
  val syringeVolumeMl: Double? = null,
  val syringeDescription: String? = null,
  val drawNeedleGauge: Int? = null,
  val drawNeedleLengthInches: Double? = null,
  val injectNeedleGauge: Int? = null,
  val injectNeedleLengthInches: Double? = null,
) {
  fun formattedDrawNeedle(): String? {
    if (drawNeedleGauge == null && drawNeedleLengthInches == null) return null
    val g = drawNeedleGauge?.let { "${it}G" } ?: ""
    val l = drawNeedleLengthInches?.let { "${formatNum(it)}\"" } ?: ""
    return listOf(g, l).filter { it.isNotEmpty() }.joinToString(" × ")
  }

  fun formattedInjectNeedle(): String? {
    if (injectNeedleGauge == null && injectNeedleLengthInches == null) return null
    val g = injectNeedleGauge?.let { "${it}G" } ?: ""
    val l = injectNeedleLengthInches?.let { "${formatNum(it)}\"" } ?: ""
    return listOf(g, l).filter { it.isNotEmpty() }.joinToString(" × ")
  }

  /**
   * Calculates volume in mL for a given dose if concentration is known.
   */
  fun calculateVolumeMl(doseAmount: Double, doseUnit: DoseUnit): Double? {
    return when (doseUnit) {
      DoseUnit.ML -> doseAmount
      DoseUnit.UNITS -> doseAmount / 100.0
      DoseUnit.MG -> {
        val conc = concentrationMgPerMl ?: return null
        if (conc > 0.0) doseAmount / conc else null
      }
      DoseUnit.MCG -> {
        val conc = concentrationMgPerMl ?: return null
        if (conc > 0.0) (doseAmount / 1000.0) / conc else null
      }
      else -> null
    }
  }
}

@Serializable
data class Medication(
  val id: String = UUID.randomUUID().toString(),
  val formulaName: String,
  val strength: String,
  val form: MedicationForm = MedicationForm.TABLET,
  val defaultDoseAmount: Double = 1.0,
  val defaultDoseUnit: DoseUnit = DoseUnit.TABLET,
  val injectableDetails: InjectableDetails? = null,
  val siteRotationEnabled: Boolean = false,
  val rotationSites: List<String> = emptyList(),
  // Pacing and safety constraints on the Medication object
  val minIntervalMinutes: Int? = null,
  val maxDailyDoses: Double? = null,
  val maxDailyAmount: Double? = null,
  // Indication
  val indicationConditionId: String? = null,
  val indicationText: String = "",
  // Optional metadata
  val prescriberId: String? = null,
  val prescribedDateIso: String? = null,
  val ndc11: String? = null,
  val brandName: String? = null,
  val manufacturer: String? = null,
  val notes: String? = null,
  // Sync-ready metadata
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
) {
  val displayTitle: String
    get() = if (!brandName.isNullOrBlank()) "$formulaName ($brandName)" else formulaName

  fun formattedDefaultDose(): String {
    val base = "${formatNum(defaultDoseAmount)} ${defaultDoseUnit.symbol}"
    val inj = injectableDetails ?: return base
    val volMl = inj.calculateVolumeMl(defaultDoseAmount, defaultDoseUnit)
    return if (volMl != null && defaultDoseUnit != DoseUnit.ML) {
      val units = (volMl * 100.0)
      "$base (${formatNum(volMl)} mL / ${formatNum(units)} U)"
    } else {
      base
    }
  }

  companion object {
    val PRESET_IM_GLUTE_SITES = listOf("Left Ventrogluteal", "Right Ventrogluteal")
    val PRESET_IM_DELT_SITES = listOf("Left Deltoid", "Right Deltoid")
    val PRESET_IM_THIGH_SITES = listOf("Left Vastus Lateralis (Thigh)", "Right Vastus Lateralis (Thigh)")
    val PRESET_IM_FULL_ROTATION = listOf(
      "Left Ventrogluteal",
      "Right Ventrogluteal",
      "Left Vastus Lateralis (Thigh)",
      "Right Vastus Lateralis (Thigh)",
      "Left Deltoid",
      "Right Deltoid",
    )
    val PRESET_SC_ABDOMEN_SITES = listOf(
      "Left Lower Abdomen",
      "Right Lower Abdomen",
      "Left Upper Abdomen",
      "Right Upper Abdomen",
    )
    val PRESET_TOPICAL_SITES = listOf(
      "Left Shoulder / Upper Arm",
      "Right Shoulder / Upper Arm",
      "Left Inner Forearm",
      "Right Inner Forearm",
    )

    fun normalizeNdc11(raw: String?): String? {
      if (raw.isNullOrBlank()) return null
      val digits = raw.filter { it.isDigit() }
      return if (digits.length == 11) {
        "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9, 11)}"
      } else {
        raw.trim()
      }
    }
  }
}

@Serializable
data class Prescriber(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val specialty: String? = null,
  val practice: String? = null,
  val phone: String? = null,
  val email: String? = null,
  val isPcp: Boolean = false,
  val careStartDateIso: String? = null,
  val careEndDateIso: String? = null,
  val notes: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
)

@Serializable
enum class ConditionStatus(val displayName: String) {
  ACTIVE("Active"),
  MANAGED("Managed / Stable"),
  REMISSION("In Remission"),
  RESOLVED("Resolved"),
}

@Serializable
data class Condition(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val details: String? = null,
  val status: ConditionStatus = ConditionStatus.ACTIVE,
  val diagnosedDateIso: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
)

@Serializable
data class TimeOfDayBucket(
  val id: String,
  val name: String,
  val hour: Int = 8,
  val minute: Int = 0,
  val endHour: Int = (hour + 2).coerceAtMost(23),
  val endMinute: Int = minute,
  val sortOrder: Int = 0,
  val notificationsEnabled: Boolean = true,
  val alarmAtWindowEnd: Boolean = true,
) {
  val startHour: Int
    get() = hour

  val startMinute: Int
    get() = minute

  fun formattedStartTime(): String = formatClockTime(hour, minute)

  fun formattedEndTime(): String = formatClockTime(endHour, endMinute)

  fun formattedTime(): String = "${formattedStartTime()} – ${formattedEndTime()}"

  companion object {
    const val BUCKET_MORNING = "bucket_morning"
    const val BUCKET_AFTERNOON = "bucket_afternoon"
    const val BUCKET_EVENING = "bucket_evening"
    const val BUCKET_BEDTIME = "bucket_bedtime"

    fun formatClockTime(h: Int, m: Int): String {
      val amPm = if (h < 12) "AM" else "PM"
      val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
      }
      return String.format("%d:%02d %s", h12, m, amPm)
    }

    fun defaultBuckets(): List<TimeOfDayBucket> = listOf(
      TimeOfDayBucket(
        id = BUCKET_MORNING,
        name = "Morning",
        hour = 7,
        minute = 0,
        endHour = 10,
        endMinute = 0,
        sortOrder = 0,
      ),
      TimeOfDayBucket(
        id = BUCKET_AFTERNOON,
        name = "Afternoon",
        hour = 12,
        minute = 0,
        endHour = 15,
        endMinute = 0,
        sortOrder = 1,
      ),
      TimeOfDayBucket(
        id = BUCKET_EVENING,
        name = "Evening",
        hour = 17,
        minute = 0,
        endHour = 20,
        endMinute = 0,
        sortOrder = 2,
      ),
      TimeOfDayBucket(
        id = BUCKET_BEDTIME,
        name = "Bedtime",
        hour = 21,
        minute = 0,
        endHour = 23,
        endMinute = 30,
        sortOrder = 3,
      ),
    )
  }
}

@Serializable
enum class ScheduleFrequencyType(val displayName: String) {
  DAILY("Every Day"),
  EVERY_N_DAYS("Every N Days (Fixed Cycle)"),
  DAYS_OF_WEEK("Specific Days of Week"),
  RELATIVE_TO_LAST_DOSE("Every N Days From Last Taken Dose"),
  AS_NEEDED("As Needed (PRN)"),
}

@Serializable
data class MedicationSchedule(
  val id: String = UUID.randomUUID().toString(),
  val medicationId: String,
  val frequencyType: ScheduleFrequencyType = ScheduleFrequencyType.DAILY,
  val bucketIds: List<String> = listOf(TimeOfDayBucket.BUCKET_MORNING),
  val intervalDays: Int = 7,
  val anchorDateIso: String = LocalDate.now().toString(),
  val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
  val doseAmount: Double = 1.0,
  val doseUnit: DoseUnit = DoseUnit.TABLET,
  val enabled: Boolean = true,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
)

@Serializable
enum class DoseStatus(val displayName: String) {
  TAKEN("Taken"),
  SKIPPED("Skipped"),
  SNOOZED("Snoozed"),
}

@Serializable
data class DoseLog(
  val id: String = UUID.randomUUID().toString(),
  val medicationId: String,
  val scheduleId: String? = null,
  val bucketId: String? = null,
  val scheduledDateIso: String,
  val actionTimestampMillis: Long = System.currentTimeMillis(),
  val status: DoseStatus = DoseStatus.TAKEN,
  val snoozedUntilMillis: Long? = null,
  val amountTaken: Double,
  val amountUnit: DoseUnit,
  val siteUsed: String? = null,
  val notes: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
)

@Serializable
enum class ThemeMode(val displayName: String) {
  SYSTEM("System Default"),
  LIGHT("Light"),
  DARK("Dark"),
}

@Serializable
data class AppSettings(
  val notificationsEnabled: Boolean = true,
  val alarmsEnabled: Boolean = true,
  val snoozeMinutes: Int = 30,
  val discreetNotifications: Boolean = false,
  val confirmBeforeTake: Boolean = false,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColor: Boolean = true,
) {
  companion object {
    val SNOOZE_PRESETS = listOf(15, 30, 45, 60)
  }
}

@Serializable
data class RegimenBackupBundle(
  val version: Int = 2,
  val exportedAtEpochMillis: Long = System.currentTimeMillis(),
  val settings: AppSettings = AppSettings(),
  val medications: List<Medication> = emptyList(),
  val prescribers: List<Prescriber> = emptyList(),
  val conditions: List<Condition> = emptyList(),
  val buckets: List<TimeOfDayBucket> = emptyList(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
)

fun formatNum(value: Double): String {
  return if (value % 1.0 == 0.0) {
    value.toLong().toString()
  } else {
    value.toBigDecimal().stripTrailingZeros().toPlainString()
  }
}

