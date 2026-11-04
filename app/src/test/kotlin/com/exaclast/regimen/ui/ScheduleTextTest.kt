package com.exaclast.regimen.ui

import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTextTest {

  private val buckets = TimeOfDayBucket.defaultBuckets()

  private fun schedule(
    type: ScheduleFrequencyType,
    interval: Int = 7,
    days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    windows: List<String> = listOf(TimeOfDayBucket.BUCKET_MORNING),
  ) = MedicationSchedule(medicationId = "m", frequencyType = type, intervalDays = interval, daysOfWeek = days, bucketIds = windows)

  @Test
  fun describesCommonSchedulesInPlainLanguage() {
    assertEquals("Daily · Morning", ScheduleText.describe(schedule(ScheduleFrequencyType.DAILY), buckets))
    assertEquals("Weekly · Morning", ScheduleText.describe(schedule(ScheduleFrequencyType.EVERY_N_DAYS), buckets))
    assertEquals(
      "Every 3 days · Morning",
      ScheduleText.describe(schedule(ScheduleFrequencyType.EVERY_N_DAYS, interval = 3), buckets),
    )
    assertEquals(
      "Weekdays · Morning",
      ScheduleText.describe(schedule(ScheduleFrequencyType.DAYS_OF_WEEK, days = (1..5).toSet()), buckets),
    )
    assertEquals(
      "Mon, Wed, Fri · Morning",
      ScheduleText.describe(schedule(ScheduleFrequencyType.DAYS_OF_WEEK, days = setOf(5, 1, 3)), buckets),
    )
    assertEquals("As needed", ScheduleText.describe(schedule(ScheduleFrequencyType.AS_NEEDED, windows = emptyList()), buckets))
  }

  @Test
  fun windowsListInTimeOrderRegardlessOfSelectionOrder() {
    val s = schedule(
      ScheduleFrequencyType.DAILY,
      windows = listOf(TimeOfDayBucket.BUCKET_EVENING, TimeOfDayBucket.BUCKET_MORNING),
    )
    assertEquals("Daily · Morning, Evening", ScheduleText.describe(s, buckets))
  }

  @Test
  fun summaryIgnoresDeletedAndDisabledSchedules() {
    val active = schedule(ScheduleFrequencyType.DAILY)
    val deleted = schedule(ScheduleFrequencyType.EVERY_N_DAYS).copy(deletedAtEpochMillis = 1L)
    val paused = schedule(ScheduleFrequencyType.EVERY_N_DAYS).copy(enabled = false)
    assertEquals("Daily · Morning", ScheduleText.summary(listOf(active, deleted, paused), buckets))
    assertEquals("As needed", ScheduleText.summary(emptyList(), buckets))
  }
}
