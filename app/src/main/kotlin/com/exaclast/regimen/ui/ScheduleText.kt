package com.exaclast.regimen.ui

import com.exaclast.regimen.R
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.Texts
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/** Plain-language schedule descriptions ("Weekly · Morning", "Mon, Wed, Fri · Evening"). */
object ScheduleText {

  fun frequency(texts: Texts, schedule: MedicationSchedule): String = when (schedule.frequencyType) {
    ScheduleFrequencyType.DAILY -> texts.get(R.string.sched_daily)
    ScheduleFrequencyType.EVERY_N_DAYS -> when (schedule.intervalDays) {
      1 -> texts.get(R.string.sched_daily)
      7 -> texts.get(R.string.sched_weekly)
      14 -> texts.get(R.string.sched_every_2_weeks)
      else -> texts.plural(R.plurals.sched_every_n_days, schedule.intervalDays, schedule.intervalDays)
    }
    ScheduleFrequencyType.DAYS_OF_WEEK -> {
      val days = schedule.daysOfWeek.sorted()
      when (days) {
        (1..7).toList() -> texts.get(R.string.sched_daily)
        (1..5).toList() -> texts.get(R.string.sched_weekdays)
        listOf(6, 7) -> texts.get(R.string.sched_weekends)
        else -> days.filter { it in 1..7 }.joinToString(", ") {
          DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, texts.locale)
        }
      }
    }
    ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE ->
      if (schedule.intervalDays == 1) {
        texts.get(R.string.sched_daily_after_last)
      } else {
        texts.plural(R.plurals.sched_every_n_days_after_last, schedule.intervalDays, schedule.intervalDays)
      }
    ScheduleFrequencyType.SPECIFIC_DATES -> {
      val count = schedule.specificDatesIso.size
      when (count) {
        0 -> texts.get(R.string.sched_specific_dates_none)
        1 -> {
          val raw = schedule.specificDatesIso.first()
          val formatted = runCatching {
            LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(texts.locale))
          }.getOrDefault(raw)
          texts.get(R.string.sched_on_date, formatted)
        }
        else -> texts.plural(R.plurals.sched_n_specific_dates, count, count)
      }
    }
    ScheduleFrequencyType.AS_NEEDED -> texts.get(R.string.sched_as_needed)
  }

  fun windows(schedule: MedicationSchedule, buckets: List<TimeOfDayBucket>): String =
    buckets.chronological().filter { it.id in schedule.bucketIds }.joinToString(", ") { it.name }

  /** "Weekly · Morning" */
  fun describe(texts: Texts, schedule: MedicationSchedule, buckets: List<TimeOfDayBucket>): String {
    val windows = windows(schedule, buckets)
    return if (schedule.frequencyType == ScheduleFrequencyType.AS_NEEDED || windows.isEmpty()) {
      frequency(texts, schedule)
    } else {
      texts.get(R.string.sched_with_windows, frequency(texts, schedule), windows)
    }
  }

  /** One-line summary of all of a medication's active schedules (finished ones are left out). */
  fun summary(
    texts: Texts,
    schedules: List<MedicationSchedule>,
    buckets: List<TimeOfDayBucket>,
    today: LocalDate = LocalDate.now(),
  ): String {
    val current = schedules.filter { it.enabled && it.deletedAtEpochMillis == null }
    val active = current.filterNot { it.hasEndedBefore(today) }
    return when {
      active.isNotEmpty() -> active.joinToString("; ") { describe(texts, it, buckets) }
      current.isNotEmpty() -> texts.get(R.string.sched_ended)
      else -> texts.get(R.string.sched_as_needed)
    }
  }
}
