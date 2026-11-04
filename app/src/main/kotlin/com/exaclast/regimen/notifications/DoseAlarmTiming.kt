package com.exaclast.regimen.notifications

import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure date/time helpers for alarm handling, kept free of Android dependencies so they can be
 * unit-tested. Receivers must never guess a dose's date from "now": a snooze can fire after
 * midnight, and overnight windows straddle two calendar days.
 */
object DoseAlarmTiming {

  /**
   * Extracts the scheduled date from a [com.exaclast.regimen.domain.ScheduledDoseItem.key]
   * (`"<scheduleId>_<bucketId>_<yyyy-MM-dd>"`). Bucket ids may themselves contain underscores,
   * so only the final segment is used. Returns null if the key carries no valid ISO date.
   */
  fun dateIsoFromItemKey(itemKey: String?): String? {
    if (itemKey.isNullOrBlank() || !itemKey.contains('_')) return null
    val candidate = itemKey.substringAfterLast('_')
    return runCatching { LocalDate.parse(candidate).toString() }.getOrNull()
  }

  /**
   * Resolves the scheduled date for a snooze-expiry alarm: explicit extra first, then the date
   * embedded in the item key, then [fallbackToday].
   */
  fun resolveSnoozeDateIso(extraDateIso: String?, itemKey: String?, fallbackToday: LocalDate): String {
    extraDateIso
      ?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
      ?.let { return it }
    return dateIsoFromItemKey(itemKey) ?: fallbackToday.toString()
  }

  /** Epoch millis at which [bucket]'s window opens for doses scheduled on [dateIso]. */
  fun windowStartMillis(bucket: TimeOfDayBucket, dateIso: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
    val date = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null
    return date
      .atTime(LocalTime.of(bucket.hour, bucket.minute))
      .atZone(zoneId)
      .toInstant()
      .toEpochMilli()
  }

  /**
   * Epoch millis at which [bucket]'s window ends for doses scheduled on [dateIso]. Overnight
   * windows end on the following calendar day.
   */
  fun windowEndMillis(bucket: TimeOfDayBucket, dateIso: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
    val date = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null
    val endDate = if (bucket.crossesMidnight) date.plusDays(1) else date
    return endDate
      .atTime(LocalTime.of(bucket.endHour, bucket.endMinute))
      .atZone(zoneId)
      .toInstant()
      .toEpochMilli()
  }
}
