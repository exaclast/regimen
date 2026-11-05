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

  /** True while [nowMillis] is inside [bucket]'s window for doses scheduled on [dateIso]. */
  fun isWithinWindow(
    bucket: TimeOfDayBucket,
    dateIso: String,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean {
    val start = windowStartMillis(bucket, dateIso, zoneId) ?: return false
    val end = windowEndMillis(bucket, dateIso, zoneId) ?: return false
    return nowMillis in start until end
  }

  /**
   * The scheduled date of [bucket]'s most recently *opened* window: today once today's window has
   * started, otherwise yesterday. A dose stays relevant (its notification is kept) from its window
   * start until the same window opens again, so an overnight window at 1 AM resolves to yesterday.
   */
  fun currentOccurrenceDateIso(
    bucket: TimeOfDayBucket,
    nowMillis: Long,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): String {
    val todayIso = today.toString()
    val todayStart = windowStartMillis(bucket, todayIso, zoneId)
    return if (todayStart != null && nowMillis >= todayStart) todayIso else today.minusDays(1).toString()
  }

  /**
   * Returns epoch millis for the next repeating reminder nudge, or null if repeat is disabled
   * (interval <= 0) or the next nudge would fire at or after [windowEndMillis].
   *
   * Nudges sit on a fixed grid anchored at [windowStartMillis] (start + k·interval), so re-arming
   * them on every data change does not keep pushing the next nudge back. Without an anchor the
   * interval is measured from [nowMillis].
   */
  fun nextNudgeMillis(
    nowMillis: Long,
    repeatIntervalMinutes: Int,
    windowEndMillis: Long?,
    windowStartMillis: Long? = null,
  ): Long? {
    if (repeatIntervalMinutes <= 0) return null
    val intervalMillis = repeatIntervalMinutes * 60_000L
    val nextMillis = if (windowStartMillis != null && windowStartMillis <= nowMillis) {
      val elapsedSlots = (nowMillis - windowStartMillis) / intervalMillis
      windowStartMillis + (elapsedSlots + 1) * intervalMillis
    } else {
      nowMillis + intervalMillis
    }
    if (windowEndMillis != null && nextMillis >= windowEndMillis) return null
    return nextMillis
  }
}
