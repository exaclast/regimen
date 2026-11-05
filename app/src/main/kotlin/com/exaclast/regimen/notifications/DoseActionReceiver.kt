package com.exaclast.regimen.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.model.DoseStatus
import java.time.LocalDate

/**
 * Handles alarm triggers, notification actions and system time/boot events.
 *
 * Every trigger first calls [DoseNotificationManager.refresh] (re-arm alarms, reconcile the shade,
 * silently), then alerts for exactly the event that fired. The end-of-window alarm screen is opened
 * by the notification's full-screen intent; receivers never start activities directly (background
 * activity starts are blocked, and the system decides between full screen and heads-up).
 */
class DoseActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val repo = RegimenRepository.getInstance(context)
    val action = intent.action ?: return

    when (action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
      -> DoseNotificationManager.refresh(context, repo.snapshot.value)

      ACTION_TRIGGER_SNOOZE_EXPIRED -> handleSnoozeExpired(context, repo, intent)

      // Older app versions scheduled snoozes as END_ALARM intents carrying an item key.
      ACTION_TRIGGER_WINDOW_END_ALARM ->
        if (intent.getStringExtra(EXTRA_ITEM_KEY) != null) {
          handleSnoozeExpired(context, repo, intent)
        } else {
          handleWindowTrigger(context, repo, intent, DoseNotificationStage.WINDOW_END_ALARM)
        }

      ACTION_TRIGGER_WINDOW_START,
      ACTION_TRIGGER_WINDOW_NUDGE,
      -> handleWindowTrigger(context, repo, intent, DoseNotificationStage.WINDOW_START_PERSISTENT)

      ACTION_TAKE, ACTION_SNOOZE, ACTION_SKIP -> handleDoseAction(context, repo, intent, action)

      // Swiped away: "seen, not now". Snooze the listed doses so the notification stays gone and
      // comes back (with sound) after the snooze length, instead of reappearing at the next
      // repeat reminder or data refresh.
      ACTION_DISMISSED -> handleDoseAction(context, repo, intent, ACTION_SNOOZE, quiet = true)
    }
  }

  /** Window start, repeating nudge, or window end: alert once for the window's actionable doses. */
  private fun handleWindowTrigger(
    context: Context,
    repo: RegimenRepository,
    intent: Intent,
    stage: DoseNotificationStage,
  ) {
    val s = repo.snapshot.value
    // Re-arm the next alarms and drop stale notifications (e.g. yesterday's missed dose for this
    // window) before alerting, so the silent reconcile can't override this event's alert.
    DoseNotificationManager.refresh(context, s, alertingBucketId = intent.getStringExtra(EXTRA_BUCKET_ID))
    if (!s.settings.notificationsEnabled) return
    if (stage == DoseNotificationStage.WINDOW_END_ALARM && !s.settings.alarmsEnabled) return

    val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID) ?: return
    val bucket = s.buckets.find { it.id == bucketId } ?: return
    if (!bucket.notificationsEnabled) return
    if (stage == DoseNotificationStage.WINDOW_END_ALARM && !bucket.alarmAtWindowEnd) return

    val nowMillis = System.currentTimeMillis()
    val dateIso = intent.getStringExtra(EXTRA_DATE_ISO)
      ?.takeIf { DoseAlarmTiming.windowStartMillis(bucket, it) != null }
      ?: DoseAlarmTiming.currentOccurrenceDateIso(bucket, nowMillis, LocalDate.now())

    // A start/nudge that arrives after the window closed (inexact fallback, device was off) is
    // not a reminder any more; the end alarm or the kept notification covers it.
    if (stage == DoseNotificationStage.WINDOW_START_PERSISTENT &&
      !DoseAlarmTiming.isWithinWindow(bucket, dateIso, nowMillis)
    ) {
      return
    }

    val items = DoseNotificationManager.actionableItems(s, bucketId, dateIso, nowMillis)
    if (items.isEmpty()) return
    DoseNotificationManager.postWindowNotification(context, bucket, dateIso, items, stage, s.settings, alert = true)
  }

  /**
   * Re-notifies a snoozed dose. The dose's date comes from the alarm (or its item key), never from
   * the current clock, so snoozes that expire after midnight or belong to overnight windows still
   * find their dose. Snooze is independent of the end-of-window alarm setting.
   */
  private fun handleSnoozeExpired(context: Context, repo: RegimenRepository, intent: Intent) {
    val s = repo.snapshot.value
    DoseNotificationManager.refresh(context, s, alertingBucketId = intent.getStringExtra(EXTRA_BUCKET_ID))
    if (!s.settings.notificationsEnabled) return

    val itemKey = intent.getStringExtra(EXTRA_ITEM_KEY) ?: return
    val dateIso = DoseAlarmTiming.resolveSnoozeDateIso(
      extraDateIso = intent.getStringExtra(EXTRA_DATE_ISO),
      itemKey = itemKey,
      fallbackToday = LocalDate.now(),
    )
    val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID)
      ?: s.buckets.firstOrNull { itemKey.contains("_${it.id}_") }?.id
      ?: return
    val nowMillis = System.currentTimeMillis()
    // Gone if completed meanwhile or re-snoozed (a newer snooze alarm will fire later).
    val item = DoseNotificationManager.actionableItems(s, bucketId, dateIso, nowMillis + SNOOZE_GRACE_MILLIS)
      .find { it.key == itemKey }
      ?: return
    if (!item.bucket.notificationsEnabled) return
    // Doses snoozed together expire together: the first alarm already re-posted (with sound)
    // the window listing this dose, so the rest stay quiet.
    if (DoseNotificationManager.isItemShown(context, bucketId, dateIso, itemKey)) return

    val windowEnd = DoseAlarmTiming.windowEndMillis(item.bucket, dateIso)
    val windowHasEnded = windowEnd != null && nowMillis >= windowEnd
    val stage = if (windowHasEnded && s.settings.alarmsEnabled && item.bucket.alarmAtWindowEnd) {
      DoseNotificationStage.WINDOW_END_ALARM
    } else {
      DoseNotificationStage.WINDOW_START_PERSISTENT
    }
    // Re-post the window with everything due in it, not just this dose.
    val items = DoseNotificationManager.actionableItems(s, bucketId, dateIso, nowMillis + SNOOZE_GRACE_MILLIS)
    DoseNotificationManager.postWindowNotification(context, item.bucket, dateIso, items, stage, s.settings, alert = true)
  }

  /**
   * Take / Snooze / Skip tapped on a window notification: applies to exactly the doses it listed
   * (doses that became due later aren't silently included). Each dose's current log is updated,
   * so a dose acted on in-app meanwhile never ends up with two logs.
   */
  private fun handleDoseAction(
    context: Context,
    repo: RegimenRepository,
    intent: Intent,
    action: String,
    quiet: Boolean = false,
  ) {
    val s = repo.snapshot.value
    val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID) ?: return
    val dateIso = intent.getStringExtra(EXTRA_DATE_ISO) ?: LocalDate.now().toString()
    val itemKeys = intent.getStringArrayExtra(EXTRA_ITEM_KEYS)?.toList()
      ?: legacyItemKey(intent, bucketId, dateIso)?.let(::listOf)
      ?: return
    val items = DoseNotificationManager.itemsFor(s, bucketId, dateIso).filter { it.key in itemKeys }

    val snoozeMins = s.settings.snoozeMinutes
    val snoozeUntil = System.currentTimeMillis() + snoozeMins * 60_000L
    val status = when (action) {
      ACTION_TAKE -> DoseStatus.TAKEN
      ACTION_SNOOZE -> DoseStatus.SNOOZED
      else -> DoseStatus.SKIPPED
    }
    val logged = items.mapNotNull { item ->
      repo.logDose(
        medicationId = item.medication.id,
        scheduleId = item.schedule.id,
        bucketId = item.bucket.id,
        scheduledDateIso = item.dateIso,
        status = status,
        amountTaken = item.schedule.doseAmount,
        amountUnit = item.schedule.doseUnit,
        siteUsed = if (status == DoseStatus.SKIPPED) null else item.suggestedSite,
        snoozedUntilMillis = if (status == DoseStatus.SNOOZED) snoozeUntil else null,
        existingLogId = item.existingLog?.id,
      )
    }

    val noun = if (logged.size == 1) "dose" else "${logged.size} doses"
    val message = when (status) {
      DoseStatus.TAKEN -> {
        val siteNote = logged.singleOrNull()?.siteUsed?.let { " ($it)" } ?: ""
        "Logged $noun taken$siteNote"
      }
      DoseStatus.SNOOZED -> "Snoozed for $snoozeMins minutes"
      DoseStatus.SKIPPED -> "Marked $noun skipped"
    }
    if (logged.isNotEmpty() && !quiet) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // Per-dose notifications posted by older app versions.
    itemKeys.forEach { DoseNotificationManager.cancelNotificationForItem(context, it) }
    val legacyNotificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
    if (legacyNotificationId != 0) {
      NotificationManagerCompat.from(context).cancel(legacyNotificationId)
    }
  }

  /** Item key from a per-dose notification posted by an older app version. */
  private fun legacyItemKey(intent: Intent, bucketId: String, dateIso: String): String? {
    intent.getStringExtra(EXTRA_ITEM_KEY)?.let { return it }
    val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return null
    return "${scheduleId}_${bucketId}_$dateIso"
  }

  companion object {
    /** Tolerates alarm delivery slightly ahead of the recorded snooze deadline. */
    private const val SNOOZE_GRACE_MILLIS = 60_000L

    const val ACTION_TAKE = "com.exaclast.regimen.action.TAKE_DOSE"
    const val ACTION_SNOOZE = "com.exaclast.regimen.action.SNOOZE_DOSE"
    const val ACTION_SKIP = "com.exaclast.regimen.action.SKIP_DOSE"
    const val ACTION_DISMISSED = "com.exaclast.regimen.action.DOSE_NOTIFICATION_DISMISSED"
    const val ACTION_TRIGGER_WINDOW_START = "com.exaclast.regimen.action.TRIGGER_WINDOW_START"
    const val ACTION_TRIGGER_WINDOW_NUDGE = "com.exaclast.regimen.action.TRIGGER_WINDOW_NUDGE"
    const val ACTION_TRIGGER_WINDOW_END_ALARM = "com.exaclast.regimen.action.TRIGGER_WINDOW_END_ALARM"
    const val ACTION_TRIGGER_SNOOZE_EXPIRED = "com.exaclast.regimen.action.TRIGGER_SNOOZE_EXPIRED"

    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_MEDICATION_ID = "extra_medication_id"
    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    const val EXTRA_BUCKET_ID = "extra_bucket_id"
    const val EXTRA_DATE_ISO = "extra_date_iso"
    const val EXTRA_AMOUNT = "extra_amount"
    const val EXTRA_UNIT = "extra_unit"
    const val EXTRA_SITE_USED = "extra_site_used"
    const val EXTRA_EXISTING_LOG_ID = "extra_existing_log_id"
    const val EXTRA_ITEM_KEY = "extra_item_key"
    const val EXTRA_ITEM_KEYS = "extra_item_keys"
  }
}
