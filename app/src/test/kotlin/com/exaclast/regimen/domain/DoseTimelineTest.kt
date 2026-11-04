package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseTimelineTest {

  private val zone = ZoneId.of("America/Chicago")
  private val date = "2026-11-03"
  private val morning = TimeOfDayBucket(id = "m", name = "Morning", hour = 7, minute = 0, endHour = 10, endMinute = 0)
  private val overnight = TimeOfDayBucket(id = "o", name = "Overnight", hour = 22, minute = 0, endHour = 2, endMinute = 0)
  private val med = Medication(id = "med", formulaName = "Metformin", strength = "500 mg")

  private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  private fun item(bucket: TimeOfDayBucket, log: DoseLog? = null) = ScheduledDoseItem(
    key = "s_${bucket.id}_$date",
    medication = med,
    schedule = MedicationSchedule(id = "s", medicationId = med.id, bucketIds = listOf(bucket.id)),
    bucket = bucket,
    dateIso = date,
    existingLog = log,
    suggestedSite = null,
    lastSiteUsed = null,
    pacing = PacingEvaluation(canTakeNow = true, isIntervalWarning = false, isDailyLimitReached = false, statusSummary = "Ready"),
    indicationCondition = null,
    prescriber = null,
  )

  private fun log(status: DoseStatus, snoozedUntil: Long? = null) = DoseLog(
    medicationId = med.id,
    scheduledDateIso = date,
    status = status,
    snoozedUntilMillis = snoozedUntil,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
  )

  @Test
  fun phaseFollowsTheClock() {
    val group = BucketDoseGroup(morning, listOf(item(morning)))
    assertEquals(WindowPhase.UPCOMING, DoseTimeline.windowPhase(group, date, at(3, 6, 59), zone))
    assertEquals(WindowPhase.NOW, DoseTimeline.windowPhase(group, date, at(3, 7), zone))
    assertEquals(WindowPhase.NOW, DoseTimeline.windowPhase(group, date, at(3, 9, 59), zone))
    assertEquals(WindowPhase.MISSED, DoseTimeline.windowPhase(group, date, at(3, 10), zone))
  }

  @Test
  fun completedWindowIsDoneRegardlessOfTime() {
    val group = BucketDoseGroup(morning, listOf(item(morning, log(DoseStatus.TAKEN))))
    assertEquals(WindowPhase.DONE, DoseTimeline.windowPhase(group, date, at(3, 6), zone))
    assertEquals(WindowPhase.DONE, DoseTimeline.windowPhase(group, date, at(3, 12), zone))
  }

  @Test
  fun overnightWindowStaysOpenPastMidnight() {
    val group = BucketDoseGroup(overnight, listOf(item(overnight)))
    assertEquals(WindowPhase.NOW, DoseTimeline.windowPhase(group, date, at(4, 1, 30), zone))
    assertEquals(WindowPhase.MISSED, DoseTimeline.windowPhase(group, date, at(4, 2), zone))
  }

  @Test
  fun snoozedPastWindowEndIsNotMissedYet() {
    val snoozed = item(morning, log(DoseStatus.SNOOZED, snoozedUntil = at(3, 10, 20)))
    assertFalse(DoseTimeline.isMissed(snoozed, at(3, 10, 5), zone))
    assertTrue(DoseTimeline.isMissed(snoozed, at(3, 10, 21), zone))
    assertEquals(at(3, 10, 20), DoseTimeline.activeSnoozeUntil(snoozed, at(3, 10, 5)))
    assertNull(DoseTimeline.activeSnoozeUntil(snoozed, at(3, 10, 21)))
  }

  @Test
  fun windowsSortByStartTimeNotManualOrder() {
    val buckets = listOf(
      overnight.copy(sortOrder = 0),
      morning.copy(sortOrder = 3),
      TimeOfDayBucket(id = "e", name = "Evening", hour = 17, endHour = 20, sortOrder = 1),
    )
    assertEquals(listOf("m", "e", "o"), buckets.chronological().map { it.id })
  }

  @Test
  fun pacingReportsWhenTheNextDoseIsAllowed() {
    val limited = med.copy(minIntervalMinutes = 240)
    val taken = log(DoseStatus.TAKEN).copy(actionTimestampMillis = at(3, 8))
    val pacing = DoseEngine.evaluatePacing(limited, listOf(taken), nowMillis = at(3, 9), todayIso = date)
    assertFalse(pacing.canTakeNow)
    assertEquals(at(3, 12), pacing.nextAllowedAtMillis)

    val later = DoseEngine.evaluatePacing(limited, listOf(taken), nowMillis = at(3, 12, 1), todayIso = date)
    assertTrue(later.canTakeNow)
    assertNull(later.nextAllowedAtMillis)
  }

  @Test
  fun clockFormatHonors24HourPreference() {
    assertEquals("9:05 PM", TimeOfDayBucket.formatClockTime(21, 5, use24Hour = false))
    assertEquals("21:05", TimeOfDayBucket.formatClockTime(21, 5, use24Hour = true))
  }
}
