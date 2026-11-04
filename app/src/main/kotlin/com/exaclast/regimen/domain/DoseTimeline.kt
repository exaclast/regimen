package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.notifications.DoseAlarmTiming
import java.time.ZoneId

/** Where a dose window sits relative to "now", from the user's point of view. */
enum class WindowPhase {
  /** Window hasn't opened yet. */
  UPCOMING,

  /** Window is open and at least one dose is still pending. */
  NOW,

  /** Window has ended with at least one dose neither taken nor skipped. */
  MISSED,

  /** Every dose in the window is taken or skipped. */
  DONE,
}

/** Pure timeline helpers for the Today screen. */
object DoseTimeline {

  fun windowPhase(
    group: BucketDoseGroup,
    dateIso: String,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): WindowPhase {
    if (group.items.isNotEmpty() && group.items.all { it.isCompleted }) return WindowPhase.DONE
    val start = DoseAlarmTiming.windowStartMillis(group.bucket, dateIso, zoneId) ?: return WindowPhase.UPCOMING
    val end = DoseAlarmTiming.windowEndMillis(group.bucket, dateIso, zoneId) ?: return WindowPhase.UPCOMING
    return when {
      nowMillis < start -> WindowPhase.UPCOMING
      nowMillis < end -> WindowPhase.NOW
      else -> WindowPhase.MISSED
    }
  }

  /** A pending dose whose window has ended (and isn't snoozed past now). */
  fun isMissed(
    item: ScheduledDoseItem,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
  ): Boolean {
    if (item.isCompleted) return false
    val log = item.existingLog
    if (log?.status == DoseStatus.SNOOZED && (log.snoozedUntilMillis ?: 0L) > nowMillis) return false
    val end = DoseAlarmTiming.windowEndMillis(item.bucket, item.dateIso, zoneId) ?: return false
    return nowMillis >= end
  }

  /** Snooze deadline if the item is currently snoozed into the future. */
  fun activeSnoozeUntil(item: ScheduledDoseItem, nowMillis: Long): Long? {
    val log = item.existingLog ?: return null
    if (log.status != DoseStatus.SNOOZED) return null
    return log.snoozedUntilMillis?.takeIf { it > nowMillis }
  }
}
