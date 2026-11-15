package com.exaclast.regimen.domain

import com.exaclast.regimen.R
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.Texts
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseAlarmTiming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class PacingEvaluation(
  val canTakeNow: Boolean,
  val isIntervalWarning: Boolean,
  val isDailyLimitReached: Boolean,
  val remainingWaitMinutes: Long = 0L,
  val dosesTakenToday: Int = 0,
  val amountTakenToday: Double = 0.0,
  val lastTakenTimestampMillis: Long? = null,
  val lastSiteUsed: String? = null,
  /** When the minimum interval elapses (epoch millis), if it hasn't yet. */
  val nextAllowedAtMillis: Long? = null,
  val maxDailyDoses: Double? = null,
  val isDailyDoseLimitReached: Boolean = false,
  val isDailyAmountLimitReached: Boolean = false,
) {
  /** One line saying why a dose can't be taken yet, or null when it can. */
  fun warningText(
    texts: Texts,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): String? {
    if (isDailyLimitReached) {
      if (isDailyDoseLimitReached && maxDailyDoses != null) {
        return texts.get(R.string.pacing_limit_reached, dosesTakenToday, formatNum(maxDailyDoses))
      }
      if (isDailyAmountLimitReached) {
        return texts.get(R.string.pacing_amount_limit_reached)
      }
      val limit = maxDailyDoses?.let { formatNum(it) } ?: return texts.get(R.string.pacing_amount_limit_reached)
      return texts.get(R.string.pacing_limit_reached, dosesTakenToday, limit)
    }
    val next = nextAllowed(nowMillis, zoneId) ?: return null
    return when (next.day) {
      NextDay.TODAY -> texts.get(R.string.pacing_warn_at, next.time)
      NextDay.TOMORROW -> texts.get(R.string.pacing_warn_tomorrow, next.time)
      NextDay.LATER -> texts.get(R.string.pacing_warn_date, next.date(texts.locale), next.time)
    }
  }

  /**
   * When the next dose is allowed: "18:18" today, "tomorrow at 18:18", or "Nov 9 at 18:18". A time
   * alone would read as today, and a long minimum interval often runs into the next day.
   */
  fun nextAllowedText(
    texts: Texts,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): String? {
    val next = nextAllowed(nowMillis, zoneId) ?: return null
    return when (next.day) {
      NextDay.TODAY -> next.time
      NextDay.TOMORROW -> texts.get(R.string.pacing_when_tomorrow, next.time)
      NextDay.LATER -> texts.get(R.string.pacing_when_date, next.date(texts.locale), next.time)
    }
  }

  private enum class NextDay { TODAY, TOMORROW, LATER }

  private class NextAllowed(val day: NextDay, val time: String, private val at: java.time.ZonedDateTime) {
    fun date(locale: Locale): String = at.format(DateTimeFormatter.ofPattern("MMM d", locale))
  }

  private fun nextAllowed(nowMillis: Long, zoneId: ZoneId): NextAllowed? {
    val next = Instant.ofEpochMilli(nextAllowedAtMillis ?: return null).atZone(zoneId)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val day = when (next.toLocalDate()) {
      today -> NextDay.TODAY
      today.plusDays(1) -> NextDay.TOMORROW
      else -> NextDay.LATER
    }
    return NextAllowed(day, TimeOfDayBucket.formatClockTime(next.hour, next.minute), next)
  }
}

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
  val indications: List<Indication>,
  val prescriber: Provider?,
  /** The first dose at a new amount (an adjustment starts today for this schedule). */
  val isNewDose: Boolean = false,
) {
  val isCompleted: Boolean
    get() = existingLog?.status == DoseStatus.TAKEN || existingLog?.status == DoseStatus.SKIPPED

  val isTaken: Boolean
    get() = existingLog?.status == DoseStatus.TAKEN

  val isSkipped: Boolean
    get() = existingLog?.status == DoseStatus.SKIPPED

  val isSnoozed: Boolean
    get() = existingLog?.status == DoseStatus.SNOOZED

  fun formattedDoseInstruction(texts: Texts): String {
    val amt = formatNum(schedule.doseAmount)
    val unit = schedule.doseUnit.symbol
    val volMl = medication.doseVolumeMl(schedule.doseAmount, schedule.doseUnit)
    val dose = if (volMl != null && schedule.doseUnit != DoseUnit.ML) {
      texts.get(R.string.dose_amount_volume, amt, unit, formatNum(volMl))
    } else {
      texts.get(R.string.dose_amount, amt, unit)
    }
    val route = medication.injectableDetails?.takeIf { medication.form.isInjectable }?.route?.code
    return if (route == null) dose else texts.get(R.string.dose_with_route, dose, route)
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
  val indications: List<Indication>,
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
   * The site to suggest for the next dose, or null when doses don't record one. Rotating continues from the
   * site actually used last; recording suggests that same site again. A last site that's no longer in the
   * list falls back to the first.
   */
  fun suggestSite(medication: Medication, allLogs: List<DoseLog>): String? =
    suggestSiteFromMedLogs(
      medication = medication,
      medLiveLogs = allLogs.filter { it.medicationId == medication.id && it.deletedAtEpochMillis == null },
    )

  private fun suggestSiteFromMedLogs(medication: Medication, medLiveLogs: List<DoseLog>): String? {
    val sites = medication.activeSites
    if (sites.isEmpty()) return null
    val last = getLastSiteUsedFromMedLogs(medLiveLogs) ?: return sites.first()
    val lastIndex = sites.indexOfFirst { it.equals(last, ignoreCase = true) }
    return when {
      lastIndex < 0 -> sites.first()
      medication.siteMode == SiteMode.ROTATE -> sites[(lastIndex + 1) % sites.size]
      else -> sites[lastIndex]
    }
  }

  /**
   * Returns the most recent site actually used for a medication, if any.
   */
  fun getLastSiteUsed(medicationId: String, allLogs: List<DoseLog>): String? =
    getLastSiteUsedFromMedLogs(
      allLogs.filter { it.medicationId == medicationId && it.deletedAtEpochMillis == null },
    )

  private fun getLastSiteUsedFromMedLogs(medLiveLogs: List<DoseLog>): String? {
    var best: DoseLog? = null
    for (log in medLiveLogs) {
      if (log.status != DoseStatus.TAKEN || log.siteUsed.isNullOrBlank()) continue
      if (best == null || log.actionTimestampMillis > best.actionTimestampMillis ||
        (log.actionTimestampMillis == best.actionTimestampMillis && log.updatedAtEpochMillis >= best.updatedAtEpochMillis)
      ) {
        best = log
      }
    }
    return best?.siteUsed
  }

  /**
   * Evaluates pacing and daily limit constraints defined on the Medication object. With [backdated],
   * [nowMillis] is a past time the dose is being logged at, so only doses taken at or before it count
   * toward the minimum interval; a later dose would otherwise read as "too soon".
   */
  fun evaluatePacing(
    medication: Medication,
    allLogs: List<DoseLog>,
    nowMillis: Long = System.currentTimeMillis(),
    todayIso: String = LocalDate.now().toString(),
    buckets: List<TimeOfDayBucket> = emptyList(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    backdated: Boolean = false,
  ): PacingEvaluation = evaluatePacingFromMedLogs(
    medication = medication,
    medLiveLogs = allLogs.filter { it.medicationId == medication.id && it.deletedAtEpochMillis == null },
    nowMillis = nowMillis,
    todayIso = todayIso,
    bucketsById = buckets.associateBy { it.id },
    zoneId = zoneId,
    backdated = backdated,
  )

  private fun evaluatePacingFromMedLogs(
    medication: Medication,
    medLiveLogs: List<DoseLog>,
    nowMillis: Long,
    todayIso: String,
    bucketsById: Map<String, TimeOfDayBucket>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    backdated: Boolean = false,
  ): PacingEvaluation {
    val activeTakenLogs = medLiveLogs.filter { it.status == DoseStatus.TAKEN }

    val lastTaken = activeTakenLogs
      .filter { !backdated || it.actionTimestampMillis <= nowMillis }
      .maxByOrNull { it.actionTimestampMillis }
    val todayLogs = activeTakenLogs.filter { limitDayOf(it, bucketsById, zoneId) == todayIso }
    val dosesTakenToday = todayLogs.size
    // The limit is in the medication's default unit; other units can't be added to it.
    val amountTakenToday = todayLogs.filter { it.amountUnit == medication.defaultDoseUnit }.sumOf { it.amountTaken }

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

    return PacingEvaluation(
      canTakeNow = !isDailyLimitReached && !isIntervalWarning,
      isIntervalWarning = isIntervalWarning,
      isDailyLimitReached = isDailyLimitReached,
      remainingWaitMinutes = remainingWaitMinutes,
      dosesTakenToday = dosesTakenToday,
      amountTakenToday = amountTakenToday,
      lastTakenTimestampMillis = lastTaken?.actionTimestampMillis,
      lastSiteUsed = lastTaken?.siteUsed,
      nextAllowedAtMillis = if (isIntervalWarning && lastTaken != null && minInterval != null) {
        lastTaken.actionTimestampMillis + minInterval * 60_000L
      } else {
        null
      },
      maxDailyDoses = maxDoses?.takeIf { it > 0 },
      isDailyDoseLimitReached = doseLimitReached,
      isDailyAmountLimitReached = amountLimitReached,
    )
  }

  /**
   * The day a taken dose counts toward for daily limits: the day it was actually taken, so
   * yesterday's missed dose taken this morning counts today. Two exceptions keep a dose on its
   * scheduled day: one taken during its own overnight window (1 AM in a 10 PM–2 AM window), and an
   * unscheduled dose logged onto a past date, where the user chose the day.
   */
  private fun limitDayOf(log: DoseLog, bucketsById: Map<String, TimeOfDayBucket>, zoneId: ZoneId): String {
    val takenOn = Instant.ofEpochMilli(log.actionTimestampMillis).atZone(zoneId).toLocalDate().toString()
    if (takenOn == log.scheduledDateIso || log.scheduleId == null) return log.scheduledDateIso
    val overnight = log.bucketId?.let { bucketsById[it] }?.takeIf { it.crossesMidnight } ?: return takenOn
    val start = DoseAlarmTiming.windowStartMillis(overnight, log.scheduledDateIso, zoneId) ?: return takenOn
    val end = DoseAlarmTiming.windowEndMillis(overnight, log.scheduledDateIso, zoneId) ?: return takenOn
    return if (log.actionTimestampMillis in start..end) log.scheduledDateIso else takenOn
  }

  /** Medications with a current schedule that can't produce doses, because its windows are gone. */
  fun medicationsWithoutWindow(
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    buckets: List<TimeOfDayBucket>,
    today: LocalDate,
  ): List<Medication> {
    val ids = schedules
      .filter { it.deletedAtEpochMillis == null && it.enabled && !it.hasEndedBefore(today) && it.hasNoValidWindow(buckets) }
      .mapTo(HashSet()) { it.medicationId }
    return medications.filter { it.deletedAtEpochMillis == null && it.id in ids }
  }

  /**
   * Determines whether a given schedule is due on targetDate.
   */
  fun isScheduleDueOnDate(
    schedule: MedicationSchedule,
    targetDate: LocalDate,
    allLogs: List<DoseLog>,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean = isScheduleDueOnDateFromMedLogs(
    schedule = schedule,
    targetDate = targetDate,
    medLiveLogs = allLogs.filter { it.deletedAtEpochMillis == null && it.medicationId == schedule.medicationId },
    zoneId = zoneId,
  )

  private fun isScheduleDueOnDateFromMedLogs(
    schedule: MedicationSchedule,
    targetDate: LocalDate,
    medLiveLogs: List<DoseLog>,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean {
    if (!schedule.enabled || schedule.deletedAtEpochMillis != null) return false

    // Outside the start/end range the schedule produces no doses, so older dates don't show a newer
    // prescription as missed. A dose that was actually logged on that date stays visible.
    if (!schedule.isInDateRange(targetDate)) {
      val targetDateIso = targetDate.toString()
      return medLiveLogs.any {
        it.scheduledDateIso == targetDateIso &&
          (it.scheduleId == null || it.scheduleId == schedule.id)
      }
    }

    return when (schedule.frequencyType) {
      ScheduleFrequencyType.DAILY -> true

      ScheduleFrequencyType.EVERY_N_DAYS -> {
        val anchor = anchorDateOf(schedule, zoneId)
        val daysBetween = ChronoUnit.DAYS.between(anchor, targetDate)
        val interval = schedule.intervalDays.coerceAtLeast(1)
        daysBetween >= 0 && (daysBetween % interval == 0L)
      }

      ScheduleFrequencyType.DAYS_OF_WEEK -> {
        schedule.daysOfWeek.contains(targetDate.dayOfWeek.value)
      }

      ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE -> {
        val targetDateIso = targetDate.toString()
        val medLogs = medLiveLogs.filter { it.status == DoseStatus.TAKEN }
        // If already taken on targetDate, keep it visible on targetDate's board
        if (medLogs.any { it.scheduledDateIso == targetDateIso }) {
          return true
        }
        // Find the most recent taken dose prior to targetDate
        val priorLogs = medLogs.filter { it.scheduledDateIso < targetDateIso }
        val latestPrior = priorLogs.maxByOrNull { it.actionTimestampMillis }
        val interval = schedule.intervalDays.coerceAtLeast(1).toLong()
        if (latestPrior == null) {
          val anchor = anchorDateOf(schedule, zoneId)
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

      ScheduleFrequencyType.SPECIFIC_DATES -> {
        schedule.specificDatesIso.contains(targetDate.toString())
      }

      ScheduleFrequencyType.AS_NEEDED -> false
    }
  }

  /**
   * Resolves a schedule's cycle anchor. An unparseable anchor (legacy data or imports) falls back to
   * the date the schedule was created, never to the date being evaluated. Falling back to the
   * evaluated date would make an every-N-days schedule due on every day.
   */
  internal fun anchorDateOf(schedule: MedicationSchedule, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
    return runCatching { LocalDate.parse(schedule.anchorDateIso.trim()) }.getOrElse {
      Instant.ofEpochMilli(schedule.createdAtEpochMillis).atZone(zoneId).toLocalDate()
    }
  }

  private data class MedDayContext(
    val med: Medication,
    val suggestedSite: String?,
    val lastSite: String?,
    val pacing: PacingEvaluation,
  )

  /**
   * Builds the full daily dashboard board for the specified date.
   */
  fun buildDailyBoard(
    dateIso: String,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    buckets: List<TimeOfDayBucket>,
    logs: List<DoseLog>,
    indications: List<Indication>,
    providers: List<Provider>,
    nowMillis: Long = System.currentTimeMillis(),
  ): DailyDoseBoard {
    val targetDate = runCatching { LocalDate.parse(dateIso) }.getOrDefault(LocalDate.now())
    val activeMedMap = medications.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val indicationMap = indications.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val prescriberMap = providers.filter { it.deletedAtEpochMillis == null }.associateBy { it.id }
    val sortedBuckets = buckets.chronological()
    val bucketsById = buckets.associateBy { it.id }

    val activeSchedules = schedules.filter {
      it.deletedAtEpochMillis == null && it.enabled && activeMedMap.containsKey(it.medicationId)
    }

    val zoneId = ZoneId.systemDefault()
    val inRangeSchedulesByMedBucket = HashMap<Pair<String, String>, MutableSet<String>>()
    activeSchedules.forEach { schedule ->
      if (schedule.frequencyType == ScheduleFrequencyType.AS_NEEDED) return@forEach
      val inRangeForDay = if (schedule.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) {
        schedule.specificDatesIso.contains(dateIso)
      } else {
        schedule.isInDateRange(targetDate)
      }
      if (!inRangeForDay) return@forEach
      schedule.bucketIds.forEach { bucketId ->
        val bucket = bucketsById[bucketId] ?: return@forEach
        if (schedule.isWindowInRange(targetDate, bucket, buckets)) {
          inRangeSchedulesByMedBucket.getOrPut(schedule.medicationId to bucketId) { HashSet() }.add(schedule.id)
        }
      }
    }

    val liveLogsByMed = logs.asSequence()
      .filter { it.deletedAtEpochMillis == null }
      .groupBy { it.medicationId }

    val logsForDateByMed = liveLogsByMed.mapValues { (_, medLogs) ->
      medLogs.filter { it.scheduledDateIso == dateIso }
    }

    val medContexts = HashMap<String, MedDayContext>()
    fun medContext(stored: Medication): MedDayContext = medContexts.getOrPut(stored.id) {
      val med = Adjustments.asOf(stored, targetDate)
      val medLogs = liveLogsByMed[stored.id].orEmpty()
      MedDayContext(
        med = med,
        suggestedSite = suggestSiteFromMedLogs(med, medLogs),
        lastSite = getLastSiteUsedFromMedLogs(medLogs),
        pacing = evaluatePacingFromMedLogs(med, medLogs, nowMillis, dateIso, bucketsById),
      )
    }

    val bucketGroups = sortedBuckets.mapNotNull { bucket ->
      val matchingSchedules = activeSchedules.filter { schedule ->
        val medLogsForDate = logsForDateByMed[schedule.medicationId].orEmpty()
        val inRangeIds = inRangeSchedulesByMedBucket[schedule.medicationId to bucket.id].orEmpty()
        val isInRangeForBucket = schedule.id in inRangeIds
        val hasLoggedDose = medLogsForDate.any {
          logMatchesItem(it, schedule, bucket, inRangeIds)
        }
        val windowEnd = DoseAlarmTiming.windowEndMillis(bucket, dateIso)
        val createdOnTargetDate = schedule.createdAtEpochMillis > 0 &&
          Instant.ofEpochMilli(schedule.createdAtEpochMillis).atZone(zoneId).toLocalDate() == targetDate
        val createdAfterWindow = schedule.startDateOrNull() == targetDate &&
          schedule.startBucketId == null &&
          createdOnTargetDate &&
          windowEnd != null &&
          schedule.createdAtEpochMillis > windowEnd

        schedule.frequencyType != ScheduleFrequencyType.AS_NEEDED &&
          schedule.bucketIds.contains(bucket.id) &&
          (!createdAfterWindow || hasLoggedDose) &&
          isScheduleDueOnDateFromMedLogs(schedule, targetDate, liveLogsByMed[schedule.medicationId].orEmpty()) &&
          // Partial first/last day or ended schedule version: skip windows outside range unless
          // that dose was actually logged AND no current in-range schedule already covers the window.
          (isInRangeForBucket || (hasLoggedDose && inRangeIds.isEmpty()))
      }

      val items = matchingSchedules.mapNotNull { schedule ->
        val stored = activeMedMap[schedule.medicationId] ?: return@mapNotNull null
        val ctx = medContext(stored)
        val inRangeIds = inRangeSchedulesByMedBucket[stored.id to bucket.id].orEmpty()
        val existingLog = logsForDateByMed[stored.id].orEmpty()
          .filter { logMatchesItem(it, schedule, bucket, inRangeIds) }
          .maxByOrNull { it.actionTimestampMillis }

        ScheduledDoseItem(
          key = "${schedule.id}_${bucket.id}_$dateIso",
          medication = ctx.med,
          schedule = Adjustments.scheduleOn(stored, schedule, targetDate),
          bucket = bucket,
          dateIso = dateIso,
          existingLog = existingLog,
          isNewDose = Adjustments.doseChangesOn(stored, schedule, targetDate),
          suggestedSite = existingLog?.takeIf { it.status == DoseStatus.TAKEN }?.siteUsed ?: ctx.suggestedSite,
          lastSiteUsed = ctx.lastSite,
          pacing = ctx.pacing,
          indications = ctx.med.indicationIds.mapNotNull { indicationMap[it] },
          prescriber = ctx.med.prescriberId?.let { prescriberMap[it] },
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
      .map { stored ->
        val ctx = medContext(stored)
        val prnSchedule = prnSchedulesByMed[stored.id]?.let { Adjustments.scheduleOn(stored, it, targetDate) }
        PrnDoseItem(
          medication = ctx.med,
          schedule = prnSchedule,
          suggestedSite = ctx.suggestedSite,
          pacing = ctx.pacing,
          indications = ctx.med.indicationIds.mapNotNull { indicationMap[it] },
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

  /**
   * Decides whether [log] records the dose for one specific (schedule, window) item.
   *
   * A schedule may span several windows (e.g. Morning + Evening), so a log must match on BOTH the
   * schedule and the window; matching on either alone would let the Morning dose complete the
   * Evening dose too.
   *
   * Logs whose schedule is no longer active for this medication (deleted or replaced) still count
   * for the same window, so a dose that was already taken is never re-prompted.
   */
  internal fun logMatchesItem(
    log: DoseLog,
    schedule: MedicationSchedule,
    bucket: TimeOfDayBucket,
    activeScheduleIdsForMed: Set<String>,
  ): Boolean {
    if (log.medicationId != schedule.medicationId) return false
    val logScheduleId = log.scheduleId
    val logBucketId = log.bucketId
    return when {
      logScheduleId != null && logBucketId != null ->
        logBucketId == bucket.id &&
          (logScheduleId == schedule.id || logScheduleId !in activeScheduleIdsForMed)
      // Legacy log without a window: only unambiguous when the schedule has a single window.
      logScheduleId != null -> logScheduleId == schedule.id && schedule.bucketIds.size == 1
      // Legacy/imported log without a schedule: match by window.
      logBucketId != null -> logBucketId == bucket.id
      // PRN / quick-log entries never complete a scheduled item.
      else -> false
    }
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

