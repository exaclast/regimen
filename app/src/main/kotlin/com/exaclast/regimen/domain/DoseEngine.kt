package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatNum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class PacingEvaluation(
  val canTakeNow: Boolean,
  val isIntervalWarning: Boolean,
  val isDailyLimitReached: Boolean,
  val remainingWaitMinutes: Long = 0L,
  val dosesTakenToday: Int = 0,
  val amountTakenToday: Double = 0.0,
  val lastTakenTimestampMillis: Long? = null,
  val lastSiteUsed: String? = null,
  val statusSummary: String,
)

data class ScheduledDoseItem(
  val key: String,
  val medication: Medication,
  val schedule: MedicationSchedule,
  val bucket: TimeOfDayBucket,
  val dateIso: String,
  val existingLog: DoseLog?,
  val suggestedSite: String?,
  val lastSiteUsed: String?,
  val pacing: PacingEvaluation,
  val indicationCondition: Condition?,
  val prescriber: Prescriber?,
) {
  val isCompleted: Boolean
    get() = existingLog?.status == DoseStatus.TAKEN || existingLog?.status == DoseStatus.SKIPPED

  val isTaken: Boolean
    get() = existingLog?.status == DoseStatus.TAKEN

  val isSkipped: Boolean
    get() = existingLog?.status == DoseStatus.SKIPPED

  val isSnoozed: Boolean
    get() = existingLog?.status == DoseStatus.SNOOZED

  fun formattedDoseInstruction(): String {
    val amt = formatNum(schedule.doseAmount)
    val unit = schedule.doseUnit.symbol
    val inj = medication.injectableDetails
    if (inj != null) {
      val volMl = inj.calculateVolumeMl(schedule.doseAmount, schedule.doseUnit)
      val volStr = if (volMl != null && schedule.doseUnit != DoseUnit.ML) {
        " (${formatNum(volMl)} mL)"
      } else {
        ""
      }
      return "$amt $unit$volStr • ${inj.route.code}"
    }
    return "$amt $unit"
  }
}

data class BucketDoseGroup(
  val bucket: TimeOfDayBucket,
  val items: List<ScheduledDoseItem>,
) {
  val pendingCount: Int
    get() = items.count { !it.isCompleted }

  val takenCount: Int
    get() = items.count { it.isTaken }
}

data class PrnDoseItem(
  val medication: Medication,
  val schedule: MedicationSchedule?,
  val suggestedSite: String?,
  val pacing: PacingEvaluation,
  val indicationCondition: Condition?,
) {
  val doseAmount: Double
    get() = schedule?.doseAmount ?: medication.defaultDoseAmount

  val doseUnit: DoseUnit
    get() = schedule?.doseUnit ?: medication.defaultDoseUnit
}

data class DailyDoseBoard(
  val dateIso: String,
  val bucketGroups: List<BucketDoseGroup>,
  val prnItems: List<PrnDoseItem>,
  val totalScheduledCount: Int,
  val totalTakenCount: Int,
  val totalSkippedCount: Int,
)

object DoseEngine {

  /**
   * Computes the next anatomical site in the medication's ordered rotation list,
   * continuing from the actual site used in the most recent TAKEN dose log.
   */
  fun computeNextSite(medication: Medication, allLogs: List<DoseLog>): String? {
    if (!medication.siteRotationEnabled || medication.rotationSites.isEmpty()) {
      return null
    }
    val sites = medication.rotationSites
    val lastTakenWithSite = allLogs
      .asSequence()
      .filter {
        it.medicationId == medication.id &&
          it.deletedAtEpochMillis == null &&
          it.status == DoseStatus.TAKEN &&
          !it.siteUsed.isNullOrBlank()
      }
      .maxByOrNull { it.actionTimestampMillis }

    if (lastTakenWithSite == null) {
      return sites.first()
    }
    val lastIndex = sites.indexOfFirst { it.equals(lastTakenWithSite.siteUsed, ignoreCase = true) }
    return if (lastIndex >= 0) {
      sites[(lastIndex + 1) % sites.size]
    } else {
      sites.first()
    }
  }

  /**
   * Returns the most recent site actually used for a medication, if any.
   */
  fun getLastSiteUsed(medicationId: String, allLogs: List<DoseLog>): String? {
    return allLogs
      .asSequence()
      .filter {
        it.medicationId == medicationId &&
          it.deletedAtEpochMillis == null &&
          it.status == DoseStatus.TAKEN &&
          !it.siteUsed.isNullOrBlank()
      }
      .maxByOrNull { it.actionTimestampMillis }
      ?.siteUsed
  }

  /**
   * Evaluates pacing and daily limit constraints defined on the Medication object.
   */
  fun evaluatePacing(
    medication: Medication,
    allLogs: List<DoseLog>,
    nowMillis: Long = System.currentTimeMillis(),
    todayIso: String = LocalDate.now().toString(),
  ): PacingEvaluation {
    val activeTakenLogs = allLogs.filter {
      it.medicationId == medication.id &&
        it.deletedAtEpochMillis == null &&
        it.status == DoseStatus.TAKEN
    }

    val lastTaken = activeTakenLogs.maxByOrNull { it.actionTimestampMillis }
    val todayLogs = activeTakenLogs.filter { it.scheduledDateIso == todayIso }
    val dosesTakenToday = todayLogs.size
    val amountTakenToday = todayLogs.sumOf { it.amountTaken }

    var remainingWaitMinutes = 0L
    val minInterval = medication.minIntervalMinutes
    if (minInterval != null && minInterval > 0 && lastTaken != null) {
      val elapsedMinutes = ((nowMillis - lastTaken.actionTimestampMillis) / 60_000L).coerceAtLeast(0L)
      if (elapsedMinutes < minInterval) {
        remainingWaitMinutes = minInterval - elapsedMinutes
      }
    }

    val maxDoses = medication.maxDailyDoses
    val maxAmount = medication.maxDailyAmount
    val doseLimitReached = maxDoses != null && maxDoses > 0 && dosesTakenToday >= maxDoses
    val amountLimitReached = maxAmount != null && maxAmount > 0 && amountTakenToday >= maxAmount
    val isDailyLimitReached = doseLimitReached || amountLimitReached
    val isIntervalWarning = remainingWaitMinutes > 0L

    val statusParts = mutableListOf<String>()
    if (isDailyLimitReached) {
      if (doseLimitReached) {
        statusParts.add("Daily limit reached ($dosesTakenToday/${formatNum(maxDoses)} doses)")
      } else if (amountLimitReached) {
        statusParts.add(
          "Daily limit reached (${formatNum(amountTakenToday)}/${formatNum(maxAmount)} ${medication.defaultDoseUnit.symbol})"
        )
      }
    } else if (isIntervalWarning) {
      val hrs = remainingWaitMinutes / 60
      val mins = remainingWaitMinutes % 60
      val waitFormatted = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
      statusParts.add("Wait $waitFormatted (min interval ${formatDurationMinutes(minInterval ?: 0)})")
    } else {
      statusParts.add("Ready")
    }

    if (!isDailyLimitReached) {
      if (maxDoses != null && maxDoses > 0) {
        statusParts.add("$dosesTakenToday/${formatNum(maxDoses)} daily doses")
      } else if (maxAmount != null && maxAmount > 0) {
        statusParts.add(
          "${formatNum(amountTakenToday)}/${formatNum(maxAmount)} ${medication.defaultDoseUnit.symbol} today"
        )
      }
    }

    return PacingEvaluation(
      canTakeNow = !isDailyLimitReached && !isIntervalWarning,
      isIntervalWarning = isIntervalWarning,
      isDailyLimitReached = isDailyLimitReached,
      remainingWaitMinutes = remainingWaitMinutes,
      dosesTakenToday = dosesTakenToday,
      amountTakenToday = amountTakenToday,
      lastTakenTimestampMillis = lastTaken?.actionTimestampMillis,
      lastSiteUsed = lastTaken?.siteUsed,
      statusSummary = statusParts.joinToString(" • "),
    )
  }

  /**
   * Determines whether a given schedule is due on targetDate.
   */
  fun isScheduleDueOnDate(
    schedule: MedicationSchedule,
    targetDate: LocalDate,
    allLogs: List<DoseLog>,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean {
    if (!schedule.enabled || schedule.deletedAtEpochMillis != null) return false

    return when (schedule.frequencyType) {
      ScheduleFrequencyType.DAILY -> true

      ScheduleFrequencyType.EVERY_N_DAYS -> {
        val anchor = runCatching { LocalDate.parse(schedule.anchorDateIso) }.getOrDefault(targetDate)
        val daysBetween = ChronoUnit.DAYS.between(anchor, targetDate)
        val interval = schedule.intervalDays.coerceAtLeast(1)
        daysBetween >= 0 && (daysBetween % interval == 0L)
      }

      ScheduleFrequencyType.DAYS_OF_WEEK -> {
        schedule.daysOfWeek.contains(targetDate.dayOfWeek.value)
      }

      ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE -> {
        val targetDateIso = targetDate.toString()
        val medLogs = allLogs.filter {
          it.medicationId == schedule.medicationId &&
            it.deletedAtEpochMillis == null &&
            it.status == DoseStatus.TAKEN
        }
        // If already taken on targetDate, keep it visible on targetDate's board
        if (medLogs.any { it.scheduledDateIso == targetDateIso }) {
          return true
        }
        // Find the most recent taken dose prior to targetDate
        val priorLogs = medLogs.filter { it.scheduledDateIso < targetDateIso }
        val latestPrior = priorLogs.maxByOrNull { it.actionTimestampMillis }
        val interval = schedule.intervalDays.coerceAtLeast(1).toLong()
        if (latestPrior == null) {
          val anchor = runCatching { LocalDate.parse(schedule.anchorDateIso) }.getOrDefault(targetDate)
          val daysFromAnchor = ChronoUnit.DAYS.between(anchor, targetDate)
          daysFromAnchor >= 0 && (daysFromAnchor % interval == 0L)
        } else {
          val lastDate = runCatching { LocalDate.parse(latestPrior.scheduledDateIso) }.getOrElse {
            Instant.ofEpochMilli(latestPrior.actionTimestampMillis).atZone(zoneId).toLocalDate()
          }
          val nextDueDate = lastDate.plusDays(interval)
          !targetDate.isBefore(nextDueDate)
        }
      }

      ScheduleFrequencyType.AS_NEEDED -> false
    }
  }

  /**
   * Builds the full daily dashboard board for the specified date.
   */
  fun buildDailyBoard(
    dateIso: String,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    buckets: List<TimeOfDayBucket>,
    logs: List<DoseLog>,
    conditions: List<Condition>,
    prescribers: List<Prescriber>,
    nowMillis: Long = System.currentTimeMillis(),
  ): DailyDoseBoard {
    val targetDate = runCatching { LocalDate.parse(dateIso) }.getOrDefault(LocalDate.now())
    val activeMedMap = medications.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val conditionMap = conditions.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val prescriberMap = prescribers.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val sortedBuckets = buckets.sortedBy { it.sortOrder }

    val activeSchedules = schedules.filter {
      it.deletedAtEpochMillis == null && it.enabled && activeMedMap.containsKey(it.medicationId)
    }

    val logsForDate = logs.filter {
      it.deletedAtEpochMillis == null && it.scheduledDateIso == dateIso
    }

    val bucketGroups = sortedBuckets.mapNotNull { bucket ->
      val matchingSchedules = activeSchedules.filter { schedule ->
        schedule.frequencyType != ScheduleFrequencyType.AS_NEEDED &&
          schedule.bucketIds.contains(bucket.id) &&
          isScheduleDueOnDate(schedule, targetDate, logs)
      }

      val items = matchingSchedules.mapNotNull { schedule ->
        val med = activeMedMap[schedule.medicationId] ?: return@mapNotNull null
        val existingLog = logsForDate
          .filter {
            it.medicationId == med.id &&
              (it.scheduleId == schedule.id || it.bucketId == bucket.id)
          }
          .maxByOrNull { it.actionTimestampMillis }

        val suggestedSite = existingLog?.siteUsed ?: computeNextSite(med, logs)
        val lastSite = getLastSiteUsed(med.id, logs)
        val pacing = evaluatePacing(med, logs, nowMillis, dateIso)

        ScheduledDoseItem(
          key = "${schedule.id}_${bucket.id}_$dateIso",
          medication = med,
          schedule = schedule,
          bucket = bucket,
          dateIso = dateIso,
          existingLog = existingLog,
          suggestedSite = suggestedSite,
          lastSiteUsed = lastSite,
          pacing = pacing,
          indicationCondition = med.indicationConditionId?.let { conditionMap[it] },
          prescriber = med.prescriberId?.let { prescriberMap[it] },
        )
      }

      if (items.isEmpty()) null else BucketDoseGroup(bucket = bucket, items = items)
    }

    // PRN / As-Needed items: medications with an AS_NEEDED schedule OR medications with no active schedules
    val scheduledMedIds = activeSchedules
      .filter { it.frequencyType != ScheduleFrequencyType.AS_NEEDED }
      .map { it.medicationId }
      .toSet()

    val prnSchedulesByMed = activeSchedules
      .filter { it.frequencyType == ScheduleFrequencyType.AS_NEEDED }
      .associateBy { it.medicationId }

    val prnItems = activeMedMap.values
      .filter { med -> prnSchedulesByMed.containsKey(med.id) || !scheduledMedIds.contains(med.id) }
      .map { med ->
        val prnSchedule = prnSchedulesByMed[med.id]
        PrnDoseItem(
          medication = med,
          schedule = prnSchedule,
          suggestedSite = computeNextSite(med, logs),
          pacing = evaluatePacing(med, logs, nowMillis, dateIso),
          indicationCondition = med.indicationConditionId?.let { conditionMap[it] },
        )
      }

    val allScheduledItems = bucketGroups.flatMap { it.items }
    return DailyDoseBoard(
      dateIso = dateIso,
      bucketGroups = bucketGroups,
      prnItems = prnItems,
      totalScheduledCount = allScheduledItems.size,
      totalTakenCount = allScheduledItems.count { it.isTaken },
      totalSkippedCount = allScheduledItems.count { it.isSkipped },
    )
  }

  fun formatDurationMinutes(minutes: Int): String {
    val hrs = minutes / 60
    val mins = minutes % 60
    return when {
      hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
      hrs > 0 -> "${hrs}h"
      else -> "${mins}m"
    }
  }
}

