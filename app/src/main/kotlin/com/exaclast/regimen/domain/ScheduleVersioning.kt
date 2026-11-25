package com.exaclast.regimen.domain

import com.exaclast.regimen.R
import com.exaclast.regimen.domain.Adjustments.EditMode
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.Labeled
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.RegimenItem
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.notifications.DoseAlarmTiming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Handles schedule edits without rewriting history or back-filling missed doses for newly added
 * windows (T-014, T-015).
 *
 * Dose changes on an existing schedule continue to use [MedicationAdjustment] on the same schedule
 * ID via [Adjustments]. Pattern changes (windows, frequency, or past specific dates) either end
 * the old schedule before the effective date and start a new version from that date ([EditMode.Change]),
 * or update the schedule in place ([EditMode.Fix]) while still guarding newly-added same-day windows
 * that have already ended.
 */
object ScheduleVersioning {

  enum class Part(override val labelRes: Int) : Labeled {
    DOSE(R.string.adjust_part_dose),
    FREQUENCY(R.string.adjust_part_frequency),
    WINDOWS(R.string.adjust_part_windows),
    DATES(R.string.adjust_part_dates),
  }

  data class Result(
    val updatedMedication: RegimenItem?,
    val schedulesToSave: List<MedicationSchedule>,
  )

  private fun effectiveBuckets(schedule: MedicationSchedule): Set<String> =
    if (schedule.frequencyType == ScheduleFrequencyType.AS_NEEDED) emptySet() else schedule.bucketIds.toSet()

  private fun usesInterval(type: ScheduleFrequencyType): Boolean =
    type == ScheduleFrequencyType.EVERY_N_DAYS || type == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE

  /** Meaningfully changed parts between [original] (at [today]) and [edited]. */
  fun changedParts(
    medication: RegimenItem,
    original: MedicationSchedule,
    edited: MedicationSchedule,
    today: LocalDate,
  ): Set<Part> = buildSet {
    if (Adjustments.doseOn(medication, original, today) != DoseAmount(edited.doseAmount, edited.doseUnit)) {
      add(Part.DOSE)
    }
    val frequencyChanged = original.frequencyType != edited.frequencyType ||
      (usesInterval(edited.frequencyType) && original.intervalDays != edited.intervalDays) ||
      (edited.frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK && original.daysOfWeek != edited.daysOfWeek)
    if (frequencyChanged) add(Part.FREQUENCY)
    if (effectiveBuckets(original) != effectiveBuckets(edited)) add(Part.WINDOWS)
    if (
      original.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES &&
      edited.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES &&
      original.specificDatesIso != edited.specificDatesIso
    ) {
      add(Part.DATES)
    }
  }

  private fun hasPastSpecificDateChange(
    original: MedicationSchedule,
    edited: MedicationSchedule,
    today: LocalDate,
  ): Boolean {
    val todayIso = today.toString()
    val origPast = original.specificDatesIso.filter { it < todayIso }.toSet()
    val editedPast = edited.specificDatesIso.filter { it < todayIso }.toSet()
    return origPast != editedPast
  }

  private fun hasPastScheduledDays(
    schedule: MedicationSchedule,
    today: LocalDate,
    zoneId: ZoneId,
  ): Boolean = when (schedule.frequencyType) {
    ScheduleFrequencyType.AS_NEEDED -> false
    ScheduleFrequencyType.SPECIFIC_DATES -> schedule.specificDatesIso.any { it < today.toString() }
    else -> {
      val start = schedule.startDateOrNull()
        ?: Instant.ofEpochMilli(schedule.createdAtEpochMillis).atZone(zoneId).toLocalDate()
      start.isBefore(today)
    }
  }

  /**
   * True when saving [edited] over [original] should ask "From a date" or "Correction".
   * Changing only future specific dates or date-range bounds (e.g. setting an end date) applies
   * directly without asking.
   */
  fun shouldAsk(
    medication: RegimenItem,
    original: MedicationSchedule?,
    edited: MedicationSchedule,
    logs: List<DoseLog>,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean {
    if (original == null) return false
    val parts = changedParts(medication, original, edited, today)
    if (parts.isEmpty()) return false
    if (parts == setOf(Part.DATES) && !hasPastSpecificDateChange(original, edited, today)) {
      return false
    }
    val hasLogs = logs.any { it.scheduleId == original.id && it.deletedAtEpochMillis == null }
    val hasAdjustments = medication.adjustments.any { a -> a.scheduleDoses.any { it.scheduleId == original.id } } ||
      (Part.DOSE in parts && medication.adjustments.isNotEmpty())
    val hasPastPatternHistory = (Part.FREQUENCY in parts || Part.WINDOWS in parts || Part.DATES in parts) &&
      hasPastScheduledDays(original, today, zoneId)
    return hasLogs || hasAdjustments || hasPastPatternHistory
  }

  fun apply(
    medication: RegimenItem,
    original: MedicationSchedule,
    edited: MedicationSchedule,
    mode: EditMode,
    today: LocalDate,
    nowMillis: Long = System.currentTimeMillis(),
    buckets: List<TimeOfDayBucket> = emptyList(),
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Result {
    val parts = changedParts(medication, original, edited, today)
    val hasPatternChange = Part.FREQUENCY in parts || Part.WINDOWS in parts || Part.DATES in parts

    if (!hasPatternChange) {
      val (updatedMed, savedSched) = Adjustments.applyScheduleEdit(medication, original, edited, mode, today)
      return Result(updatedMed, listOf(savedSched))
    }

    if (mode is EditMode.Fix) {
      val (updatedMed, doseFixed) = Adjustments.applyScheduleEdit(medication, original, edited, EditMode.Fix, today)
      val guarded = guardSameDayAddedWindows(
        original = original,
        edited = doseFixed,
        today = today,
        nowMillis = nowMillis,
        buckets = buckets,
        zoneId = zoneId,
      )
      return Result(updatedMed, listOf(guarded))
    }

    val change = mode as EditMode.Change
    val effectiveDate = runCatching { LocalDate.parse(change.dateIso.trim()) }.getOrDefault(today)
    val effectiveIso = effectiveDate.toString()

    // 1. Close out the old schedule version before effectiveDate.
    val closedOld: MedicationSchedule? = when (original.frequencyType) {
      ScheduleFrequencyType.AS_NEEDED -> {
        // As-needed has no missed-dose history; soft-delete so it doesn't stay active alongside the new version.
        original.copy(updatedAtEpochMillis = nowMillis, deletedAtEpochMillis = nowMillis)
      }
      ScheduleFrequencyType.SPECIFIC_DATES -> {
        val pastDates = original.specificDatesIso.filter { iso ->
          runCatching { LocalDate.parse(iso.trim()) }.getOrNull()?.isBefore(effectiveDate) == true
        }.toSet()
        if (pastDates.isEmpty()) {
          original.copy(updatedAtEpochMillis = nowMillis, deletedAtEpochMillis = nowMillis)
        } else {
          original.copy(specificDatesIso = pastDates, updatedAtEpochMillis = nowMillis)
        }
      }
      else -> {
        val oldEnd = effectiveDate.minusDays(1)
        val origStart = original.startDateOrNull()
        val existingEnd = original.endDateOrNull()
        when {
          origStart != null && origStart.isAfter(oldEnd) ->
            original.copy(updatedAtEpochMillis = nowMillis, deletedAtEpochMillis = nowMillis)
          existingEnd != null && !existingEnd.isAfter(oldEnd) ->
            null // Already ended before effectiveDate.
          else ->
            original.copy(
              endDateIso = oldEnd.toString(),
              endBucketId = null,
              updatedAtEpochMillis = nowMillis,
            )
        }
      }
    }

    // 2. Create the new schedule version starting on effectiveDate.
    val newId = UUID.randomUUID().toString()
    val startDose = if (Part.DOSE in parts) {
      DoseAmount(edited.doseAmount, edited.doseUnit)
    } else {
      Adjustments.doseOn(medication, original, effectiveDate)
    }
    val newEndDate = edited.endDateOrNull()?.takeIf { !it.isBefore(effectiveDate) }?.toString()
    val newEndBucket = edited.endBucketId.takeIf { newEndDate != null && newEndDate == edited.endDateIso }
    val preserveAnchor = edited.usesDateRange &&
      Part.FREQUENCY !in parts &&
      edited.startDateIso == original.startDateIso
    val newStartBucket = edited.startBucketId.takeIf { it != original.startBucketId }
    val newSpecificDates = if (edited.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) {
      edited.specificDatesIso.filter { iso ->
        runCatching { LocalDate.parse(iso.trim()) }.getOrNull()?.let { !it.isBefore(effectiveDate) } == true
      }.toSet().ifEmpty { edited.specificDatesIso }
    } else {
      emptySet()
    }

    val newSchedule = edited.copy(
      id = newId,
      doseAmount = startDose.amount,
      doseUnit = startDose.unit,
      anchorDateIso = when {
        !edited.usesDateRange -> edited.anchorDateIso
        preserveAnchor -> original.anchorDateIso
        else -> effectiveIso
      },
      specificDatesIso = newSpecificDates,
      startDateIso = if (edited.usesDateRange) effectiveIso else null,
      endDateIso = if (edited.usesDateRange) newEndDate else null,
      startBucketId = if (edited.usesDateRange) newStartBucket else null,
      endBucketId = if (edited.usesDateRange) newEndBucket else null,
      createdAtEpochMillis = nowMillis,
      updatedAtEpochMillis = nowMillis,
      deletedAtEpochMillis = null,
    )

    // 3. Migrate future MedicationAdjustment scheduleDoses from original.id to newId.
    val migratedMedication = migrateAdjustments(
      medication = medication,
      oldScheduleId = original.id,
      newScheduleId = newId,
      effectiveDate = effectiveDate,
      newDose = startDose,
      note = change.note?.trim()?.ifEmpty { null },
      doseChanged = Part.DOSE in parts,
    ).takeIf { it != medication }

    val toSave = buildList {
      if (closedOld != null) add(closedOld)
      add(newSchedule)
    }
    return Result(migratedMedication, toSave)
  }

  /**
   * When editing in Fix mode on [today], newly-added windows that already ended before [nowMillis]
   * must not be back-filled as missed today.
   */
  private fun guardSameDayAddedWindows(
    original: MedicationSchedule,
    edited: MedicationSchedule,
    today: LocalDate,
    nowMillis: Long,
    buckets: List<TimeOfDayBucket>,
    zoneId: ZoneId,
  ): MedicationSchedule {
    if (!edited.usesDateRange || edited.frequencyType == ScheduleFrequencyType.AS_NEEDED) return edited
    if (edited.startDateOrNull() != today) return edited

    val origBuckets = effectiveBuckets(original)
    val addedBuckets = edited.bucketIds.toSet() - origBuckets
    if (addedBuckets.isEmpty() && original.frequencyType != ScheduleFrequencyType.AS_NEEDED) return edited

    val todayIso = today.toString()
    val orderedChosen = buckets.chronological().filter { it.id in edited.bucketIds }
    if (orderedChosen.isEmpty()) return edited

    val hasEndedAddedToday = orderedChosen.any { b ->
      b.id in addedBuckets &&
        (DoseAlarmTiming.windowEndMillis(b, todayIso, zoneId)?.let { it <= nowMillis } == true)
    }
    if (!hasEndedAddedToday) return edited

    if (original.frequencyType == ScheduleFrequencyType.AS_NEEDED) {
      return edited.copy(createdAtEpochMillis = nowMillis)
    }

    if (edited.startBucketId != null) return edited

    // Skip already-ended newly-added windows today while keeping windows that were already in
    // original or haven't ended yet.
    val firstActive = orderedChosen.firstOrNull { b ->
      b.id in origBuckets ||
        (DoseAlarmTiming.windowEndMillis(b, todayIso, zoneId)?.let { it > nowMillis } ?: true)
    }
    return when {
      firstActive == null -> edited.copy(createdAtEpochMillis = nowMillis)
      firstActive.id != orderedChosen.first().id -> edited.copy(startBucketId = firstActive.id)
      else -> edited
    }
  }

  private fun migrateAdjustments(
    medication: RegimenItem,
    oldScheduleId: String,
    newScheduleId: String,
    effectiveDate: LocalDate,
    newDose: DoseAmount,
    note: String?,
    doseChanged: Boolean,
  ): RegimenItem {
    var result = medication
    medication.adjustments.forEach { adjustment ->
      val adjDate = adjustment.effectiveDateOrNull() ?: return@forEach
      if (adjustment.scheduleDoses.none { it.scheduleId == oldScheduleId }) return@forEach
      val updatedDoses = when {
        adjDate.isAfter(effectiveDate) -> adjustment.scheduleDoses.map { d ->
          if (d.scheduleId == oldScheduleId) d.copy(scheduleId = newScheduleId) else d
        }
        adjDate == effectiveDate -> adjustment.scheduleDoses.filterNot { it.scheduleId == oldScheduleId }
        else -> adjustment.scheduleDoses
      }
      if (updatedDoses != adjustment.scheduleDoses) {
        result = Adjustments.replace(result, adjustment.copy(scheduleDoses = updatedDoses))
      }
    }
    if (doseChanged && !note.isNullOrBlank()) {
      result = Adjustments.upsert(
        result,
        MedicationAdjustment(
          effectiveDateIso = effectiveDate.toString(),
          note = note,
          scheduleDoses = listOf(ScheduleDose(newScheduleId, newDose.amount, newDose.unit)),
        ),
      )
    }
    return result
  }
}
