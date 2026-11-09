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
    indications = emptyList(),
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

  private fun board(vararg groups: BucketDoseGroup) = DailyDoseBoard(
    dateIso = date,
    bucketGroups = groups.toList(),
    prnItems = emptyList(),
    totalScheduledCount = 0,
    totalTakenCount = 0,
    totalSkippedCount = 0,
  )

  private val openOvernight = BucketDoseGroup(overnight, listOf(item(overnight)))

  @Test
  fun overnightDoseIsCarriedOntoTodayWhileItsWindowIsOpen() {
    val yesterday = board(openOvernight, BucketDoseGroup(morning, listOf(item(morning))))
    assertEquals(listOf(openOvernight), DoseTimeline.stillOpenFromYesterday(yesterday, at(4, 1), zone))
  }

  @Test
  fun overnightDoseIsNotCarriedOnceItsWindowCloses() {
    assertTrue(DoseTimeline.stillOpenFromYesterday(board(openOvernight), at(4, 2), zone).isEmpty())
  }

  @Test
  fun completedOvernightDoseIsNotCarried() {
    val done = BucketDoseGroup(overnight, listOf(item(overnight, log(DoseStatus.TAKEN))))
    assertTrue(DoseTimeline.stillOpenFromYesterday(board(done), at(4, 1), zone).isEmpty())
  }

  @Test
  fun missedCountLeavesOutOpenWindowsAndCompletedDoses() {
    val yesterday = board(
      openOvernight,
      BucketDoseGroup(morning, listOf(item(morning), item(morning, log(DoseStatus.TAKEN)))),
    )
    assertEquals(1, DoseTimeline.missedCount(yesterday, at(4, 1), zone))
    assertEquals(2, DoseTimeline.missedCount(yesterday, at(4, 3), zone))
  }

  @Test
  fun nextDosePrefersLastNightsOpenDoseThenTheEarliestToday() {
    val today = board(
      BucketDoseGroup(morning, listOf(item(morning, log(DoseStatus.TAKEN)))),
      BucketDoseGroup(overnight.copy(id = "later"), listOf(item(overnight.copy(id = "later")))),
    )
    assertEquals("o", DoseTimeline.nextDoseFor(med.id, listOf(openOvernight), today)?.bucket?.id)
    assertEquals("later", DoseTimeline.nextDoseFor(med.id, emptyList(), today)?.bucket?.id)
    assertNull(DoseTimeline.nextDoseFor("other", listOf(openOvernight), today))
  }

  @Test
  fun pacingWarningExplainsTheLimit() {
    val base = PacingEvaluation(canTakeNow = false, isIntervalWarning = false, isDailyLimitReached = true, statusSummary = "")
    val now = at(3, 9)
    assertEquals("Daily amount limit reached", base.warningText(now, zone))
    assertEquals("Daily limit reached (2 of 2)", base.copy(dosesTakenToday = 2, maxDailyDoses = 2.0).warningText(now, zone))
    val waiting = base.copy(isDailyLimitReached = false, isIntervalWarning = true, nextAllowedAtMillis = at(3, 12))
    assertEquals("Next dose allowed at 12:00 PM", waiting.warningText(now, zone))
    assertNull(base.copy(canTakeNow = true, isDailyLimitReached = false).warningText(now, zone))
  }

  @Test
  fun pacingWarningSaysWhichDayTheNextDoseIsAllowed() {
    val waiting = PacingEvaluation(canTakeNow = false, isIntervalWarning = true, isDailyLimitReached = false, statusSummary = "")
    val now = at(3, 22)
    val tomorrow = waiting.copy(nextAllowedAtMillis = at(4, 18))
    assertEquals("tomorrow at 6:00 PM", tomorrow.nextAllowedText(now, zone))
    assertEquals("Next dose allowed tomorrow at 6:00 PM", tomorrow.warningText(now, zone))
    val later = waiting.copy(nextAllowedAtMillis = at(9, 18))
    assertEquals("Next dose allowed Nov 9 at 6:00 PM", later.warningText(now, zone))
  }

  @Test
  fun clockFormatHonors24HourPreference() {
    assertEquals("9:05 PM", TimeOfDayBucket.formatClockTime(21, 5, use24Hour = false))
    assertEquals("21:05", TimeOfDayBucket.formatClockTime(21, 5, use24Hour = true))
  }
}
