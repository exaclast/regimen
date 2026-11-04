package com.exaclast.regimen.notifications

import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoseAlarmTimingTest {

  private val zone = ZoneId.of("America/New_York")

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
}
