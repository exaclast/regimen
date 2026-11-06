package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseLogMatchingTest {

  private val date = "2026-11-03"
  private val med = Medication(id = "med_metformin", formulaName = "Metformin", strength = "500 mg")
  private val twiceDaily = MedicationSchedule(
    id = "sched_bid",
    medicationId = med.id,
    bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING, TimeOfDayBucket.BUCKET_EVENING),
  )

  private fun log(scheduleId: String?, bucketId: String?, status: DoseStatus = DoseStatus.TAKEN) = DoseLog(
    medicationId = med.id,
    scheduleId = scheduleId,
    bucketId = bucketId,
    scheduledDateIso = date,
    status = status,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
  )

  private fun board(schedules: List<MedicationSchedule>, logs: List<DoseLog>) = DoseEngine.buildDailyBoard(
    dateIso = date,
    medications = listOf(med),
    schedules = schedules,
    buckets = TimeOfDayBucket.defaultBuckets(),
    logs = logs,
    conditions = emptyList(),
    providers = emptyList(),
  )

  private fun DailyDoseBoard.completedByBucket(): Map<String, Boolean> =
    bucketGroups.flatMap { it.items }.associate { it.bucket.id to it.isCompleted }

  @Test
  fun takingMorningDose_doesNotCompleteEveningDoseOfSameSchedule() {
    val result = board(listOf(twiceDaily), listOf(log(twiceDaily.id, TimeOfDayBucket.BUCKET_MORNING)))
      .completedByBucket()

    assertEquals(true, result[TimeOfDayBucket.BUCKET_MORNING])
    assertEquals(false, result[TimeOfDayBucket.BUCKET_EVENING])
  }

  @Test
  fun bothDosesLogged_completeBothWindows() {
    val result = board(
      listOf(twiceDaily),
      listOf(
        log(twiceDaily.id, TimeOfDayBucket.BUCKET_MORNING),
        log(twiceDaily.id, TimeOfDayBucket.BUCKET_EVENING, DoseStatus.SKIPPED),
      ),
    ).completedByBucket()

    assertEquals(true, result[TimeOfDayBucket.BUCKET_MORNING])
    assertEquals(true, result[TimeOfDayBucket.BUCKET_EVENING])
  }

  @Test
  fun legacyLogWithoutBucket_matchesOnlySingleWindowSchedules() {
    val onceDaily = twiceDaily.copy(id = "sched_qd", bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING))

    assertTrue(board(listOf(onceDaily), listOf(log(onceDaily.id, null))).completedByBucket().values.single())
    // Ambiguous for a multi-window schedule: must not complete either window.
    assertTrue(board(listOf(twiceDaily), listOf(log(twiceDaily.id, null))).completedByBucket().values.none { it })
  }

  @Test
  fun logFromReplacedSchedule_stillCountsForSameWindow() {
    // Dose was taken under a schedule that has since been deleted/replaced.
    val replacement = twiceDaily.copy(id = "sched_new")
    val result = board(listOf(replacement), listOf(log("sched_deleted", TimeOfDayBucket.BUCKET_MORNING)))
      .completedByBucket()

    assertEquals(true, result[TimeOfDayBucket.BUCKET_MORNING])
    assertEquals(false, result[TimeOfDayBucket.BUCKET_EVENING])
  }

  @Test
  fun logFromAnotherActiveScheduleInSameWindow_doesNotCrossComplete() {
    val extraMonday = MedicationSchedule(
      id = "sched_extra",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
    )
    val items = board(listOf(twiceDaily, extraMonday), listOf(log(twiceDaily.id, TimeOfDayBucket.BUCKET_MORNING)))
      .bucketGroups.flatMap { it.items }
      .filter { it.bucket.id == TimeOfDayBucket.BUCKET_MORNING }
      .associate { it.schedule.id to it.isCompleted }

    assertEquals(true, items[twiceDaily.id])
    assertEquals(false, items[extraMonday.id])
  }

  @Test
  fun prnLog_neverCompletesScheduledDose() {
    val result = board(listOf(twiceDaily), listOf(log(null, null))).completedByBucket()
    assertTrue(result.values.none { it })
  }

  @Test
  fun malformedAnchorDate_fallsBackToCreationDate_notEveryDay() {
    val zone = ZoneId.of("UTC")
    val created = LocalDate.of(2026, 11, 3)
    val weekly = MedicationSchedule(
      id = "sched_weekly",
      medicationId = med.id,
      frequencyType = ScheduleFrequencyType.EVERY_N_DAYS,
      intervalDays = 7,
      anchorDateIso = "11/03/2026",
      createdAtEpochMillis = created.atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    val due = (0L..13L).map { offset ->
      DoseEngine.isScheduleDueOnDate(weekly, created.plusDays(offset), emptyList(), zone)
    }
    assertEquals(listOf(0, 7), due.withIndex().filter { it.value }.map { it.index })
    assertFalse(DoseEngine.isScheduleDueOnDate(weekly, created.plusDays(1), emptyList(), zone))
  }
}
