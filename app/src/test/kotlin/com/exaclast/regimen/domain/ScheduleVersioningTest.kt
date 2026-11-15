package com.exaclast.regimen.domain

import com.exaclast.regimen.domain.Adjustments.EditMode
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule window additions and versioning (T-014, T-015). */
class ScheduleVersioningTest {

  private val zone = ZoneId.systemDefault()
  private val today = LocalDate.parse("2026-11-15")
  private val morning = TimeOfDayBucket(id = "bucket_morning", name = "Morning", hour = 7, endHour = 10)
  private val evening = TimeOfDayBucket(id = "bucket_evening", name = "Evening", hour = 17, endHour = 20)
  private val buckets = listOf(morning, evening)

  private fun atHour(date: LocalDate, hour: Int): Long =
    date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

  private val med = Medication(
    id = "med_1",
    formulaName = "Metformin",
    strength = "500 mg",
  )

  private val originalMorning = MedicationSchedule(
    id = "sched_1",
    medicationId = med.id,
    frequencyType = ScheduleFrequencyType.DAILY,
    bucketIds = listOf(morning.id),
    startDateIso = "2026-11-01",
    createdAtEpochMillis = atHour(LocalDate.parse("2026-11-01"), 8),
  )

  private fun boardItems(
    dateIso: String,
    schedules: List<MedicationSchedule>,
    logs: List<DoseLog> = emptyList(),
    nowMillis: Long = atHour(LocalDate.parse(dateIso), 14),
    medication: Medication = med,
  ) = DoseEngine.buildDailyBoard(
    dateIso = dateIso,
    medications = listOf(medication),
    schedules = schedules,
    buckets = buckets,
    logs = logs,
    indications = emptyList(),
    providers = emptyList(),
    nowMillis = nowMillis,
  )

  @Test
  fun addingWindowFromToday_doesNotBackfillPastMissedDoses() {
    val log = DoseLog(
      medicationId = med.id,
      scheduleId = originalMorning.id,
      bucketId = morning.id,
      scheduledDateIso = "2026-11-10",
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    val edited = originalMorning.copy(bucketIds = listOf(morning.id, evening.id))

    assertTrue(ScheduleVersioning.shouldAsk(med, originalMorning, edited, listOf(log), today, zone))

    val now = atHour(today, 14)
    val result = ScheduleVersioning.apply(
      medication = med,
      original = originalMorning,
      edited = edited,
      mode = EditMode.Change(today.toString()),
      today = today,
      nowMillis = now,
      buckets = buckets,
      zoneId = zone,
    )
    assertNull(result.updatedMedication)
    assertEquals(2, result.schedulesToSave.size)
    val closedOld = result.schedulesToSave[0]
    val newVersion = result.schedulesToSave[1]

    assertEquals(originalMorning.id, closedOld.id)
    assertEquals("2026-11-14", closedOld.endDateIso)
    assertNotEquals(originalMorning.id, newVersion.id)
    assertEquals("2026-11-15", newVersion.startDateIso)
    assertEquals(listOf(morning.id, evening.id), newVersion.bucketIds)

    // Yesterday (Nov 14): only Morning, never Evening!
    val yesterdayBoard = boardItems("2026-11-14", result.schedulesToSave)
    assertEquals(listOf(morning.id), yesterdayBoard.bucketGroups.map { it.bucket.id })

    // Tomorrow (Nov 16): both Morning and Evening!
    val tomorrowBoard = boardItems("2026-11-16", result.schedulesToSave)
    assertEquals(listOf(morning.id, evening.id), tomorrowBoard.bucketGroups.map { it.bucket.id })
  }

  @Test
  fun addingEarlierWindowToday_doesNotMarkItMissedToday() {
    // Schedule started Nov 1 with Evening only; on Nov 15 at 2 PM user adds Morning (ended at 10 AM).
    val originalEvening = originalMorning.copy(bucketIds = listOf(evening.id))
    val edited = originalEvening.copy(bucketIds = listOf(morning.id, evening.id))
    val now = atHour(today, 14)

    val result = ScheduleVersioning.apply(
      medication = med,
      original = originalEvening,
      edited = edited,
      mode = EditMode.Change(today.toString()),
      today = today,
      nowMillis = now,
      buckets = buckets,
      zoneId = zone,
    )

    val todayBoard = boardItems("2026-11-15", result.schedulesToSave, nowMillis = now)
    // Morning already ended at 10 AM before the new version was created at 2 PM -> not missed today.
    assertEquals(listOf(evening.id), todayBoard.bucketGroups.map { it.bucket.id })
    // Tomorrow has both windows.
    assertEquals(listOf(morning.id, evening.id), boardItems("2026-11-16", result.schedulesToSave).bucketGroups.map { it.bucket.id })
  }

  @Test
  fun addingEarlierWindowInSameDayFixMode_withoutHistory_doesNotMarkMissedToday() {
    // Created at 8 AM today with Evening only, no logs yet; at 2 PM user adds Morning in Fix mode.
    val morningCreated = atHour(today, 8)
    val sameDayEvening = MedicationSchedule(
      id = "sched_same_day",
      medicationId = med.id,
      bucketIds = listOf(evening.id),
      startDateIso = today.toString(),
      createdAtEpochMillis = morningCreated,
    )
    val edited = sameDayEvening.copy(bucketIds = listOf(morning.id, evening.id))

    assertFalse(ScheduleVersioning.shouldAsk(med, sameDayEvening, edited, emptyList(), today, zone))

    val now = atHour(today, 14)
    val result = ScheduleVersioning.apply(
      medication = med,
      original = sameDayEvening,
      edited = edited,
      mode = EditMode.Fix,
      today = today,
      nowMillis = now,
      buckets = buckets,
      zoneId = zone,
    )
    assertEquals(1, result.schedulesToSave.size)
    val saved = result.schedulesToSave.single()
    assertEquals(evening.id, saved.startBucketId)

    val todayBoard = boardItems("2026-11-15", listOf(saved), nowMillis = now)
    assertEquals(listOf(evening.id), todayBoard.bucketGroups.map { it.bucket.id })
    assertEquals(listOf(morning.id, evening.id), boardItems("2026-11-16", listOf(saved)).bucketGroups.map { it.bucket.id })
  }

  @Test
  fun addingWindowToUnloggedPastSchedule_asksChangeOrFix() {
    val edited = originalMorning.copy(bucketIds = listOf(morning.id, evening.id))
    assertTrue(ScheduleVersioning.shouldAsk(med, originalMorning, edited, emptyList(), today, zone))
  }

  @Test
  fun changingFrequencyVersionsScheduleAndPreservesPastHistory() {
    val edited = originalMorning.copy(
      frequencyType = ScheduleFrequencyType.DAYS_OF_WEEK,
      daysOfWeek = setOf(1, 3, 5), // Mon, Wed, Fri
    )
    val result = ScheduleVersioning.apply(
      medication = med,
      original = originalMorning,
      edited = edited,
      mode = EditMode.Change("2026-11-15"),
      today = today,
      nowMillis = atHour(today, 9),
      buckets = buckets,
      zoneId = zone,
    )

    // Nov 12 (Thursday): old schedule was Daily -> due!
    assertEquals(listOf(morning.id), boardItems("2026-11-12", result.schedulesToSave).bucketGroups.map { it.bucket.id })
    // Nov 17 (Tuesday): new schedule is Mon/Wed/Fri -> not due!
    assertTrue(boardItems("2026-11-17", result.schedulesToSave).bucketGroups.isEmpty())
    // Nov 18 (Wednesday): new schedule -> due!
    assertEquals(listOf(morning.id), boardItems("2026-11-18", result.schedulesToSave).bucketGroups.map { it.bucket.id })
  }

  @Test
  fun futurePlannedDoseAdjustmentsMigrateToNewScheduleVersion() {
    val medWithSteps = med.copy(
      adjustments = listOf(
        MedicationAdjustment(
          id = "past_step",
          effectiveDateIso = "2026-11-05",
          scheduleDoses = listOf(ScheduleDose(originalMorning.id, 2.0, DoseUnit.TABLET)),
        ),
        MedicationAdjustment(
          id = "future_step",
          effectiveDateIso = "2026-12-01",
          scheduleDoses = listOf(ScheduleDose(originalMorning.id, 3.0, DoseUnit.TABLET)),
        ),
      ),
    )
    val edited = originalMorning.copy(
      bucketIds = listOf(morning.id, evening.id),
      doseAmount = 2.0, // editor shows today's dose
    )
    val result = ScheduleVersioning.apply(
      medication = medWithSteps,
      original = originalMorning,
      edited = edited,
      mode = EditMode.Change(today.toString()),
      today = today,
      nowMillis = atHour(today, 9),
      buckets = buckets,
      zoneId = zone,
    )

    val updatedMed = result.updatedMedication
    assertNotNull(updatedMed)
    val oldVersion = result.schedulesToSave[0]
    val newVersion = result.schedulesToSave[1]

    // Past step remains attached to oldVersion; future step migrated to newVersion.
    assertEquals(2.0, Adjustments.doseOn(updatedMed!!, oldVersion, LocalDate.parse("2026-11-10")).amount, 0.0)
    assertEquals(2.0, newVersion.doseAmount, 0.0)
    assertEquals(3.0, Adjustments.doseOn(updatedMed, newVersion, LocalDate.parse("2026-12-01")).amount, 0.0)
  }

  @Test
  fun settingEndDateOrFutureSpecificDateDoesNotAskOrVersion() {
    val log = DoseLog(
      medicationId = med.id,
      scheduleId = originalMorning.id,
      bucketId = morning.id,
      scheduledDateIso = "2026-11-10",
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    val ended = originalMorning.copy(endDateIso = "2026-11-25")
    assertFalse(ScheduleVersioning.shouldAsk(med, originalMorning, ended, listOf(log), today, zone))

    val specific = MedicationSchedule(
      id = "sched_spec",
      medicationId = med.id,
      frequencyType = ScheduleFrequencyType.SPECIFIC_DATES,
      specificDatesIso = setOf("2026-11-05"),
    )
    val specificLog = log.copy(scheduleId = specific.id, scheduledDateIso = "2026-11-05")
    val addedFutureDate = specific.copy(specificDatesIso = setOf("2026-11-05", "2026-11-25"))
    assertFalse(ScheduleVersioning.shouldAsk(med, specific, addedFutureDate, listOf(specificLog), today, zone))

    val changedPastDate = specific.copy(specificDatesIso = setOf("2026-11-06"))
    assertTrue(ScheduleVersioning.shouldAsk(med, specific, changedPastDate, listOf(specificLog), today, zone))
  }

  @Test
  fun switchingFromAsNeededSoftDeletesOldAsNeededSchedule() {
    val prn = MedicationSchedule(
      id = "sched_prn",
      medicationId = med.id,
      frequencyType = ScheduleFrequencyType.AS_NEEDED,
      bucketIds = emptyList(),
    )
    val timed = prn.copy(
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(morning.id),
    )
    val result = ScheduleVersioning.apply(
      medication = med,
      original = prn,
      edited = timed,
      mode = EditMode.Change(today.toString()),
      today = today,
      nowMillis = atHour(today, 6),
      buckets = buckets,
      zoneId = zone,
    )
    assertEquals(2, result.schedulesToSave.size)
    assertNotNull(result.schedulesToSave[0].deletedAtEpochMillis)
    assertNull(result.schedulesToSave[1].deletedAtEpochMillis)
    assertEquals("2026-11-15", result.schedulesToSave[1].startDateIso)
  }

  @Test
  fun doseOnlyEditDelegatesToAdjustmentsWithoutSplittingSchedule() {
    val log = DoseLog(
      medicationId = med.id,
      scheduleId = originalMorning.id,
      bucketId = morning.id,
      scheduledDateIso = "2026-11-10",
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    val editedDoseOnly = originalMorning.copy(doseAmount = 2.0)
    assertTrue(ScheduleVersioning.shouldAsk(med, originalMorning, editedDoseOnly, listOf(log), today, zone))
    val result = ScheduleVersioning.apply(
      medication = med,
      original = originalMorning,
      edited = editedDoseOnly,
      mode = EditMode.Change("2026-11-20", "Titrate up"),
      today = today,
      nowMillis = atHour(today, 9),
      buckets = buckets,
      zoneId = zone,
    )
    assertEquals(1, result.schedulesToSave.size)
    assertEquals(originalMorning.id, result.schedulesToSave.single().id)
    assertEquals(2.0, Adjustments.doseOn(result.updatedMedication!!, result.schedulesToSave.single(), LocalDate.parse("2026-11-20")).amount, 0.0)
  }
}
