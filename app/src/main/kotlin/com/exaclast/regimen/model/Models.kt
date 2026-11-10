package com.exaclast.regimen.model

import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Process-wide clock preference. Updated from the device setting by the activity and the notification
 * manager (`DateFormat.is24HourFormat`); defaults to 12-hour so pure-JVM code and tests are stable.
 */
object ClockPreferences {
  @Volatile
  var use24Hour: Boolean = false
}

/** Windows in time-of-day order. */
fun List<TimeOfDayBucket>.chronological(): List<TimeOfDayBucket> = sortedWith(TimeOfDayBucket.CHRONOLOGICAL)

@Serializable
enum class MedicationForm(val displayName: String, val supportsSiteRotation: Boolean) {
  TABLET("Tablet", false),
  CAPSULE("Capsule", false),
  INJECTABLE_VIAL("Injectable (Vial)", true),
  INJECTABLE_PEN("Injectable (Pen / Auto-injector / Prefilled syringe)", true),
  TOPICAL("Topical (Gel / Cream / Patch)", true),
  LIQUID("Oral Liquid / Solution", false),
  INHALER("Inhaler / Nasal Spray", false),
  DROPS("Drops (Ophthalmic / Otic)", false),
  OTHER("Other", false);

  val isInjectable: Boolean
    get() = this == INJECTABLE_VIAL || this == INJECTABLE_PEN

  /** Prefilled devices (pens, auto-injectors, prefilled syringes) have nothing to draw up. */
  val isPrefilledInjector: Boolean
    get() = this == INJECTABLE_PEN
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
  UNITS("units", "Units (syringe or pen)"),
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
  // Indications: the indications this treats. Many-to-many with indications, edited from either side.
  val indicationIds: List<String> = emptyList(),
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

  /** The name most people recognize: brand when known, otherwise the generic name. */
  val primaryName: String
    get() = brandName?.takeIf { it.isNotBlank() } ?: formulaName

  /** The generic name when [primaryName] is a brand; null otherwise. */
  val secondaryName: String?
    get() = formulaName.takeIf { !brandName.isNullOrBlank() }

  /**
   * Volume to draw up for a dose, or null when it doesn't apply. Prefilled devices are dialed or
   * fixed, not drawn up, and "units" only convert to mL on a U-100 syringe: a U-200 pen's 20 units
   * is 0.1 mL, not 0.2.
   */
  fun doseVolumeMl(amount: Double, unit: DoseUnit): Double? =
    if (form.isPrefilledInjector) null else injectableDetails?.calculateVolumeMl(amount, unit)

  fun formattedDefaultDose(): String {
    val base = "${formatNum(defaultDoseAmount)} ${defaultDoseUnit.symbol}"
    val volMl = doseVolumeMl(defaultDoseAmount, defaultDoseUnit)
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
data class Provider(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val specialty: String? = null,
  val practice: String? = null,
  val phone: String? = null,
  val email: String? = null,
  val isPcp: Boolean = false,
  /** Someone the patient keeps going back to, as opposed to a specialist seen only when needed. */
  val seenRegularly: Boolean = false,
  val careStartDateIso: String? = null,
  val careEndDateIso: String? = null,
  val notes: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
) {
  val isRegular: Boolean
    get() = isPcp || seenRegularly
}

@Serializable
enum class IndicationStatus(val displayName: String) {
  ACTIVE("Active"),
  MANAGED("Managed / Stable"),
  REMISSION("In Remission"),
  RESOLVED("Resolved"),
}

/**
 * Why a medication is taken. Acute (an infection, an injury) is short-lived, chronic is ongoing, and
 * preventive (PrEP, PEP) stops something happening. Declared in display order: acute comes first
 * because it usually needs attention soonest.
 */
@Serializable
enum class IndicationKind(val displayName: String) {
  ACUTE("Acute"),
  CHRONIC("Chronic"),
  PREVENTIVE("Preventive");

  /** Statuses that make sense for this kind: acute and preventive are either active or over. */
  val statuses: List<IndicationStatus>
    get() = when (this) {
      ACUTE, PREVENTIVE -> listOf(IndicationStatus.ACTIVE, IndicationStatus.RESOLVED)
      CHRONIC -> IndicationStatus.entries
    }

  /** With no end date of its own, it ends with its linked medication courses, if they all end. */
  val endsWithCourse: Boolean
    get() = this != CHRONIC

  /** Nothing is "resolved" about prevention, so a preventive course is "Completed" instead. */
  fun statusLabel(status: IndicationStatus): String =
    if (this == PREVENTIVE && status == IndicationStatus.RESOLVED) "Completed" else status.displayName
}

@Serializable
data class Indication(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val details: String? = null,
  val kind: IndicationKind = IndicationKind.CHRONIC,
  val status: IndicationStatus = IndicationStatus.ACTIVE,
  /** When it started or was diagnosed. */
  val diagnosedDateIso: String? = null,
  /**
   * Last day of the indication (inclusive); after it the indication is archived. An acute or preventive
   * one without an end date ends with its linked medication courses (see `IndicationTimeline`).
   */
  val endDateIso: String? = null,
  /** Providers helping manage it. A provider can manage many indications and vice versa. */
  val providerIds: List<String> = emptyList(),
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
  /** For sync conflict tie-breaks. 0 = never edited (a built-in default). */
  val updatedAtEpochMillis: Long = 0L,
) {
  val startHour: Int
    get() = hour

  val startMinute: Int
    get() = minute

  fun formattedStartTime(): String = formatClockTime(hour, minute)

  fun formattedEndTime(): String = formatClockTime(endHour, endMinute)

  fun formattedTime(): String = "${formattedStartTime()} – ${formattedEndTime()}"

  val crossesMidnight: Boolean
    get() = (endHour * 60 + endMinute) < (hour * 60 + minute)

  companion object {
    const val BUCKET_MORNING = "bucket_morning"
    const val BUCKET_AFTERNOON = "bucket_afternoon"
    const val BUCKET_EVENING = "bucket_evening"
    const val BUCKET_BEDTIME = "bucket_bedtime"

    /** Formats a wall-clock time using the device's 12/24-hour preference ([ClockPreferences]). */
    fun formatClockTime(h: Int, m: Int, use24Hour: Boolean = ClockPreferences.use24Hour): String {
      val pattern = if (use24Hour) "HH:mm" else "h:mm a"
      return LocalTime.of(h, m).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    /** Windows are always presented in time-of-day order (start time, then name). */
    val CHRONOLOGICAL: Comparator<TimeOfDayBucket> =
      compareBy<TimeOfDayBucket>({ it.hour * 60 + it.minute }, { it.name.lowercase() })

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

    /**
     * The windows left after "Restore default windows": the four defaults, plus any custom window
     * a schedule still uses. Removing one of those would silently stop that medication's doses.
     */
    fun windowsAfterRestoringDefaults(
      current: List<TimeOfDayBucket>,
      usedWindowIds: Set<String>,
    ): List<TimeOfDayBucket> {
      val defaults = defaultBuckets()
      val defaultIds = defaults.mapTo(HashSet()) { it.id }
      return defaults + current.filter { it.id !in defaultIds && it.id in usedWindowIds }
    }
  }
}

@Serializable
enum class ScheduleFrequencyType(val displayName: String) {
  DAILY("Every Day"),
  EVERY_N_DAYS("Every N Days (Fixed Cycle)"),
  DAYS_OF_WEEK("Specific Days of Week"),
  RELATIVE_TO_LAST_DOSE("Every N Days From Last Taken Dose"),
  SPECIFIC_DATES("Specific Dates"),
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
  val specificDatesIso: Set<String> = emptySet(),
  val doseAmount: Double = 1.0,
  val doseUnit: DoseUnit = DoseUnit.TABLET,
  val enabled: Boolean = true,
  /** First day the schedule applies (inclusive); null = no lower bound. */
  val startDateIso: String? = null,
  /** Last day the schedule applies (inclusive); null = ongoing. */
  val endDateIso: String? = null,
  /** First dose window on the start date (e.g. picked up a prescription in the evening); null = all. */
  val startBucketId: String? = null,
  /** Last dose window on the end date; null = all. */
  val endBucketId: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  val deletedAtEpochMillis: Long? = null,
) {
  /** Specific-date and as-needed schedules aren't bounded by a start/end range. */
  val usesDateRange: Boolean
    get() = frequencyType != ScheduleFrequencyType.SPECIFIC_DATES &&
      frequencyType != ScheduleFrequencyType.AS_NEEDED

  fun startDateOrNull(): LocalDate? = startDateIso?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

  fun endDateOrNull(): LocalDate? = endDateIso?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

  /** The last day a timed schedule produces doses; null when it's open-ended. */
  fun lastDayOrNull(): LocalDate? =
    if (frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) {
      specificDatesIso.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }.maxOrNull()
    } else {
      endDateOrNull()
    }

  /** True when [date] falls inside the schedule's start/end range (always true when unbounded). */
  fun isInDateRange(date: LocalDate): Boolean {
    if (!usesDateRange) return true
    val start = startDateOrNull()
    val end = endDateOrNull()
    return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
  }

  /**
   * True when [bucket]'s dose on [date] is inside the range, including the partial first and last
   * days: on the start date only windows at or after [startBucketId] count, on the end date only
   * windows at or before [endBucketId]. Windows compare by start time; a boundary window that no
   * longer exists doesn't restrict anything.
   */
  fun isWindowInRange(date: LocalDate, bucket: TimeOfDayBucket, buckets: List<TimeOfDayBucket>): Boolean {
    if (!isInDateRange(date)) return false
    if (!usesDateRange) return true
    fun minutesOf(b: TimeOfDayBucket) = b.hour * 60 + b.minute
    val minutes = minutesOf(bucket)
    if (date == startDateOrNull()) {
      val first = startBucketId?.let { id -> buckets.find { it.id == id } }
      if (first != null && minutes < minutesOf(first)) return false
    }
    if (date == endDateOrNull()) {
      val last = endBucketId?.let { id -> buckets.find { it.id == id } }
      if (last != null && minutes > minutesOf(last)) return false
    }
    return true
  }

  /** Needs a window to produce doses but none of its windows exist (e.g. after a restore). */
  fun hasNoValidWindow(buckets: List<TimeOfDayBucket>): Boolean =
    frequencyType != ScheduleFrequencyType.AS_NEEDED && bucketIds.none { id -> buckets.any { it.id == id } }

  /** The schedule's end date has passed: it no longer produces doses. */
  fun hasEndedBefore(date: LocalDate): Boolean {
    if (!usesDateRange) return false
    return endDateOrNull()?.isBefore(date) == true
  }
}

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

/** How the medication list is split into sections. */
@Serializable
enum class MedicationGrouping(val displayName: String) {
  SCHEDULE("Schedule"),
  INDICATION("Indication"),
  FORM("Form"),
}

@Serializable
data class AppSettings(
  val notificationsEnabled: Boolean = true,
  val alarmsEnabled: Boolean = true,
  val snoozeMinutes: Int = 30,
  val reminderRepeatMinutes: Int = 15,
  val discreetNotifications: Boolean = false,
  val confirmBeforeTake: Boolean = false,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColor: Boolean = true,
  val permissionsPromptDismissedVersion: Int = 0,
  val allowFdaLookup: Boolean = false,
  val patientName: String = "",
  /** Track providers (tab, fields). null = not chosen yet: on only if any provider exists. */
  val trackProviders: Boolean? = null,
  /** Track indications (tab, fields). null = not chosen yet: on only if any indication exists. */
  @OptIn(ExperimentalSerializationApi::class)
  @JsonNames("trackConditions") // Stored settings saved before the rename to indications.
  val trackIndications: Boolean? = null,
  val medicationGrouping: MedicationGrouping = MedicationGrouping.SCHEDULE,
  /** When a [SharedSettings] field last changed on this device; 0 = never. */
  val sharedSettingsUpdatedAtEpochMillis: Long = 0L,
) {
  /** The settings every synced device shares; everything else (reminders, theme...) is per device. */
  fun shared(): SharedSettings = SharedSettings(
    patientName = patientName,
    trackProviders = trackProviders,
    trackIndications = trackIndications,
    updatedAtEpochMillis = sharedSettingsUpdatedAtEpochMillis,
  )

  fun withShared(shared: SharedSettings): AppSettings = copy(
    patientName = shared.patientName,
    trackProviders = shared.trackProviders,
    trackIndications = shared.trackIndications,
    sharedSettingsUpdatedAtEpochMillis = shared.updatedAtEpochMillis,
  )

  companion object {
    const val CURRENT_PERMISSIONS_VERSION = 2
    val SNOOZE_PRESETS = listOf(15, 30, 45, 60)
    val REPEAT_PRESETS = listOf(0, 10, 15, 20, 30)
  }
}

/** Settings that sync across devices. Reminder, alarm, theme and privacy settings stay per device. */
@Serializable
data class SharedSettings(
  val patientName: String = "",
  val trackProviders: Boolean? = null,
  val trackIndications: Boolean? = null,
  val updatedAtEpochMillis: Long = 0L,
) {
  /** Equality of the user-visible values, ignoring when they were set. */
  fun sameValues(other: SharedSettings): Boolean = copy(updatedAtEpochMillis = 0L) == other.copy(updatedAtEpochMillis = 0L)
}

/** A device that has synced. Any device can log; [PrimaryClaim] decides conflicts. */
@Serializable
data class SyncDevice(
  val id: String,
  val name: String,
  val model: String = "",
  val firstSeenEpochMillis: Long = 0L,
  val lastSyncedEpochMillis: Long = 0L,
)

/**
 * Which device is primary. A device takes over by writing a claim with a higher [epoch], so the
 * newest claim wins without the old primary having to be reachable.
 */
@Serializable
data class PrimaryClaim(
  val deviceId: String,
  val epoch: Long,
  val claimedAtEpochMillis: Long,
) {
  /** Total order: higher epoch wins; equal epochs (simultaneous claims) break by device id. */
  fun supersedes(other: PrimaryClaim?): Boolean =
    other == null || epoch > other.epoch || (epoch == other.epoch && deviceId > other.deviceId)
}

/**
 * Everything a backup or sync carries. [settings] is the exporting device's own settings, used
 * only when restoring a full backup onto a device; [sharedSettings] is what syncs.
 */
@Serializable
data class RegimenBackupBundle(
  val version: Int = CURRENT_VERSION,
  val exportedAtEpochMillis: Long = System.currentTimeMillis(),
  val sourceDeviceId: String? = null,
  val settings: AppSettings = AppSettings(),
  val sharedSettings: SharedSettings = SharedSettings(),
  val devices: List<SyncDevice> = emptyList(),
  val primary: PrimaryClaim? = null,
  val medications: List<Medication> = emptyList(),
  val providers: List<Provider> = emptyList(),
  val indications: List<Indication> = emptyList(),
  val buckets: List<TimeOfDayBucket> = emptyList(),
  val schedules: List<MedicationSchedule> = emptyList(),
  val doseLogs: List<DoseLog> = emptyList(),
) {
  companion object {
    const val CURRENT_VERSION = 1
  }
}

/**
 * A number for display: at most three decimals, or three significant digits below 1 so very small
 * volumes don't round away. Removes floating-point noise (7.000000000000001 → 7). To pre-fill an
 * editable field, use [formatExact] so saving doesn't change the stored value.
 */
fun formatNum(value: Double): String {
  if (!value.isFinite()) return value.toString()
  val exact = value.toBigDecimal()
  val rounded = if (kotlin.math.abs(value) >= 1.0) {
    exact.setScale(3, RoundingMode.HALF_UP)
  } else {
    exact.round(MathContext(3, RoundingMode.HALF_UP))
  }
  return rounded.stripTrailingZeros().toPlainString()
}

/** The stored value with nothing rounded, for pre-filling editable fields. */
fun formatExact(value: Double): String {
  if (!value.isFinite()) return value.toString()
  return value.toBigDecimal().stripTrailingZeros().toPlainString()
}

