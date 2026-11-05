package com.exaclast.regimen.ui

import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import java.time.LocalDate

/** Plain-language schedule descriptions ("Weekly · Morning", "Mon, Wed, Fri · Evening"). */
object ScheduleText {

  private val DAY_NAMES = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

  fun frequency(schedule: MedicationSchedule): String = when (schedule.frequencyType) {
    ScheduleFrequencyType.DAILY -> "Daily"
    ScheduleFrequencyType.EVERY_N_DAYS -> when (schedule.intervalDays) {
      1 -> "Daily"
      7 -> "Weekly"
      14 -> "Every 2 weeks"
      else -> "Every ${schedule.intervalDays} days"
    }
    ScheduleFrequencyType.DAYS_OF_WEEK -> {
      val days = schedule.daysOfWeek.sorted()
      when (days) {
        (1..7).toList() -> "Daily"
        (1..5).toList() -> "Weekdays"
        listOf(6, 7) -> "Weekends"
        else -> days.mapNotNull { DAY_NAMES[it] }.joinToString(", ")
      }
    }
    ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE ->
      if (schedule.intervalDays == 1) "Daily after last dose" else "Every ${schedule.intervalDays} days after last dose"
    ScheduleFrequencyType.SPECIFIC_DATES -> {
      val count = schedule.specificDatesIso.size
      when (count) {
        0 -> "Specific dates"
        1 -> "On ${schedule.specificDatesIso.first()}"
        else -> "$count specific dates"
      }
    }
    ScheduleFrequencyType.AS_NEEDED -> "As needed"
  }

  fun windows(schedule: MedicationSchedule, buckets: List<TimeOfDayBucket>): String =
    buckets.chronological().filter { it.id in schedule.bucketIds }.joinToString(", ") { it.name }

  /** "Weekly · Morning" */
  fun describe(schedule: MedicationSchedule, buckets: List<TimeOfDayBucket>): String {
    val windows = windows(schedule, buckets)
    return if (schedule.frequencyType == ScheduleFrequencyType.AS_NEEDED || windows.isEmpty()) {
      frequency(schedule)
    } else {
      "${frequency(schedule)} · $windows"
    }
  }

  /** One-line summary of all of a medication's active schedules (finished ones are left out). */
  fun summary(
    schedules: List<MedicationSchedule>,
    buckets: List<TimeOfDayBucket>,
    today: LocalDate = LocalDate.now(),
  ): String {
    val current = schedules.filter { it.enabled && it.deletedAtEpochMillis == null }
    val active = current.filterNot { it.hasEndedBefore(today) }
    return when {
      active.isNotEmpty() -> active.joinToString("; ") { describe(it, buckets) }
      current.isNotEmpty() -> "Schedule ended"
      else -> "As needed"
    }
  }
}
