package com.exaclast.regimen.notifications

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseAlarmTimingTest {

  private val zone = ZoneId.of("America/New_York")

  private fun snoozedLog(
    until: Long?,
    status: DoseStatus = DoseStatus.SNOOZED,
    scheduleId: String? = "s1",
    deletedAt: Long? = null,
  ) = DoseLog(
    medicationId = "m1",
    scheduleId = scheduleId,
    bucketId = "b1",
    scheduledDateIso = "2026-11-05",
    status = status,
    snoozedUntilMillis = until,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
    deletedAtEpochMillis = deletedAt,
  )

  @Test
  fun pendingSnoozes_includesRunningSnoozeWithItsDoseDate() {
    val snooze = DoseAlarmTiming.pendingSnoozes(listOf(snoozedLog(until = 2_000)), nowMillis = 1_000).single()
    assertEquals("s1_b1_2026-11-05", snooze.itemKey)
    assertEquals("b1", snooze.bucketId)
    assertEquals("2026-11-05", snooze.dateIso)
    assertEquals(2_000L, snooze.triggerAtMillis)
  }

  @Test
  fun pendingSnoozes_skipsEverythingWithNothingToWakeFor() {
    val logs = listOf(
      snoozedLog(until = 500), // already over
      snoozedLog(until = null),
      snoozedLog(until = 2_000, status = DoseStatus.TAKEN),
      snoozedLog(until = 2_000, deletedAt = 900),
      snoozedLog(until = 2_000, scheduleId = null),
    )
    assertTrue(DoseAlarmTiming.pendingSnoozes(logs, nowMillis = 1_000).isEmpty())
  }

  @Test
  fun dateIsoFromItemKey_handlesUnderscoredBucketIdsAndUuids() {
    assertEquals(
      "2026-11-03",
      DoseAlarmTiming.dateIsoFromItemKey("sched_med_daily_bucket_morning_2026-11-03"),
    )
    assertEquals(
      "2026-12-31",
      DoseAlarmTiming.dateIsoFromItemKey("3f2a9c1e-7b1d-4c2e-9f00-1234567890ab_9d8e7f6a-0000-1111-2222-333344445555_2026-12-31"),
    )
    assertNull(DoseAlarmTiming.dateIsoFromItemKey("sched_bucket_notadate"))
    assertNull(DoseAlarmTiming.dateIsoFromItemKey("nodelimiter"))
    assertNull(DoseAlarmTiming.dateIsoFromItemKey(null))
  }

  @Test
  fun snoozeDate_comesFromAlarmNotFromClock() {
    val afterMidnight = LocalDate.of(2026, 11, 4)
    val key = "sched_x_bucket_bedtime_2026-11-03"

    // Bedtime snoozed at 11:40 PM expires at 12:10 AM: still yesterday's dose.
    assertEquals("2026-11-03", DoseAlarmTiming.resolveSnoozeDateIso("2026-11-03", key, afterMidnight))
    // Legacy alarm without the extra: recover from the key.
    assertEquals("2026-11-03", DoseAlarmTiming.resolveSnoozeDateIso(null, key, afterMidnight))
    // Garbage extra falls through to the key.
    assertEquals("2026-11-03", DoseAlarmTiming.resolveSnoozeDateIso("bogus", key, afterMidnight))
    // Nothing usable: today.
    assertEquals("2026-11-04", DoseAlarmTiming.resolveSnoozeDateIso(null, "bad", afterMidnight))
  }

  @Test
  fun windowEnd_sameDayAndOvernight() {
    val bedtime = TimeOfDayBucket(id = "b", name = "Bedtime", hour = 21, minute = 0, endHour = 23, endMinute = 30)
    val overnight = TimeOfDayBucket(id = "o", name = "Overnight", hour = 22, minute = 0, endHour = 2, endMinute = 0)

    assertEquals(
      LocalDateTime.of(2026, 11, 3, 23, 30).atZone(zone).toInstant().toEpochMilli(),
      DoseAlarmTiming.windowEndMillis(bedtime, "2026-11-03", zone),
    )
    assertEquals(
      LocalDateTime.of(2026, 11, 4, 2, 0).atZone(zone).toInstant().toEpochMilli(),
      DoseAlarmTiming.windowEndMillis(overnight, "2026-11-03", zone),
    )
    assertNull(DoseAlarmTiming.windowEndMillis(bedtime, "not-a-date", zone))
  }

  @Test
  fun nextNudgeMillis_calculatesCorrectlyAndStopsAtWindowEnd() {
    val baseMillis = 1_000_000L
    // Repeat off (0)
    assertNull(DoseAlarmTiming.nextNudgeMillis(baseMillis, 0, baseMillis + 2_000_000L))

    // 15 min repeat: 15 * 60 * 1000 = 900,000 ms
    val expectedNudge = baseMillis + 900_000L
    assertEquals(expectedNudge, DoseAlarmTiming.nextNudgeMillis(baseMillis, 15, baseMillis + 1_800_000L))

    // Next nudge is at or past window end: returns null
    assertNull(DoseAlarmTiming.nextNudgeMillis(baseMillis, 15, baseMillis + 900_000L))
    assertNull(DoseAlarmTiming.nextNudgeMillis(baseMillis, 15, baseMillis + 500_000L))

    // Open-ended window (windowEndMillis is null)
    assertEquals(expectedNudge, DoseAlarmTiming.nextNudgeMillis(baseMillis, 15, null))
  }

  @Test
  fun nextNudgeMillis_staysOnGridAnchoredAtWindowStart() {
    val start = 0L
    val end = 3 * 60 * 60_000L // 3h window
    val min = 60_000L

    // Re-arming at 7 or 14 minutes in must not push the 15-minute nudge back.
    assertEquals(15 * min, DoseAlarmTiming.nextNudgeMillis(7 * min, 15, end, start))
    assertEquals(15 * min, DoseAlarmTiming.nextNudgeMillis(14 * min, 15, end, start))
    // Exactly on a grid point (the nudge itself firing): the next slot.
    assertEquals(30 * min, DoseAlarmTiming.nextNudgeMillis(15 * min, 15, end, start))
    // Last slot would land on the window end: no nudge, the end alarm takes over.
    assertNull(DoseAlarmTiming.nextNudgeMillis(166 * min, 15, end, start))
    // Before the window opens, fall back to measuring from now.
    assertEquals(5 * min, DoseAlarmTiming.nextNudgeMillis(-10 * min, 15, end, start))
  }

  @Test
  fun currentOccurrence_isMostRecentlyOpenedWindow() {
    val morning = TimeOfDayBucket(id = "m", name = "Morning", hour = 7, minute = 0, endHour = 10, endMinute = 0)
    val overnight = TimeOfDayBucket(id = "o", name = "Overnight", hour = 22, minute = 0, endHour = 2, endMinute = 0)
    val today = LocalDate.of(2026, 11, 4)
    fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 4, h, m).atZone(zone).toInstant().toEpochMilli()

    // Before today's morning window: yesterday's missed morning dose is still the current one.
    assertEquals("2026-11-03", DoseAlarmTiming.currentOccurrenceDateIso(morning, at(6, 59), today, zone))
    assertEquals("2026-11-04", DoseAlarmTiming.currentOccurrenceDateIso(morning, at(7, 0), today, zone))
    assertEquals("2026-11-04", DoseAlarmTiming.currentOccurrenceDateIso(morning, at(18, 0), today, zone))

    // 1 AM belongs to the overnight window that opened yesterday at 10 PM.
    assertEquals("2026-11-03", DoseAlarmTiming.currentOccurrenceDateIso(overnight, at(1, 0), today, zone))
    assertEquals("2026-11-04", DoseAlarmTiming.currentOccurrenceDateIso(overnight, at(22, 30), today, zone))
  }

  @Test
  fun isWithinWindow_handlesOvernightAndBoundaries() {
    val overnight = TimeOfDayBucket(id = "o", name = "Overnight", hour = 22, minute = 0, endHour = 2, endMinute = 0)
    fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    assertEquals(true, DoseAlarmTiming.isWithinWindow(overnight, "2026-11-03", at(3, 22, 0), zone))
    assertEquals(true, DoseAlarmTiming.isWithinWindow(overnight, "2026-11-03", at(4, 1, 59), zone))
    // End is exclusive: the end alarm fires exactly at the boundary.
    assertEquals(false, DoseAlarmTiming.isWithinWindow(overnight, "2026-11-03", at(4, 2, 0), zone))
    assertEquals(false, DoseAlarmTiming.isWithinWindow(overnight, "2026-11-04", at(4, 1, 0), zone))
  }
}
