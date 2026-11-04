package com.exaclast.regimen.ui.components

import android.text.format.DateFormat
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale-aware date/time formatting for UI text (field order and month names follow the device locale). */
object UiFormat {

  private fun pattern(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
  }

  /** "Tue, Nov 3" (adds the year when it isn't the current year). */
  fun date(date: LocalDate, withWeekday: Boolean = true): String {
    val sameYear = date.year == LocalDate.now().year
    val skeleton = buildString {
      if (withWeekday) append("EEE")
      append("MMMd")
      if (!sameYear) append("y")
    }
    return date.format(pattern(skeleton))
  }

  fun dateIso(iso: String?, withWeekday: Boolean = false): String? =
    iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { date(it, withWeekday) }

  /** Wall-clock time for an instant, honoring the 12/24-hour preference. */
  fun time(epochMillis: Long): String {
    val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return TimeOfDayBucket.formatClockTime(t.hour, t.minute)
  }

  /** "Nov 3, 8:12 AM" — or "Today, 8:12 AM" / "Yesterday, 8:12 AM". */
  fun dateTime(epochMillis: Long): String {
    val day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val dayLabel = when (day) {
      today -> "Today"
      today.minusDays(1) -> "Yesterday"
      else -> date(day, withWeekday = false)
    }
    return "$dayLabel, ${time(epochMillis)}"
  }
}
