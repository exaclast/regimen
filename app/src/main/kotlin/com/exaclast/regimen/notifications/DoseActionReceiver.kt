package com.exaclast.regimen.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import java.time.LocalDate

class DoseActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val repo = RegimenRepository.getInstance(context)
    val action = intent.action ?: return

    if (
      action == Intent.ACTION_BOOT_COMPLETED ||
      action == Intent.ACTION_MY_PACKAGE_REPLACED ||
      action == Intent.ACTION_TIME_CHANGED ||
      action == Intent.ACTION_TIMEZONE_CHANGED ||
      action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    ) {
      val s = repo.snapshot.value
      DoseNotificationManager.scheduleDailyBucketAlarms(context, s.buckets, s.settings)
      DoseNotificationManager.syncActiveWindowNotifications(context, s)
      return
    }

    if (action == ACTION_TRIGGER_WINDOW_START || action == ACTION_TRIGGER_WINDOW_END_ALARM) {
      val s = repo.snapshot.value
      // Immediately chain the next day's exact window start and end alarms
      DoseNotificationManager.scheduleDailyBucketAlarms(context, s.buckets, s.settings)
      if (!s.settings.notificationsEnabled) return

      val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID)
      val targetItemKey = intent.getStringExtra(EXTRA_ITEM_KEY)
      val stage = if (action == ACTION_TRIGGER_WINDOW_END_ALARM) {
        if (!s.settings.alarmsEnabled) return
        DoseNotificationStage.WINDOW_END_ALARM
      } else {
        DoseNotificationStage.WINDOW_START_PERSISTENT
      }

      val board = DoseEngine.buildDailyBoard(
        dateIso = LocalDate.now().toString(),
        medications = s.medications,
        schedules = s.schedules,
        buckets = s.buckets,
        logs = s.doseLogs,
        conditions = s.conditions,
        prescribers = s.prescribers,
      )

      val candidateItems = if (bucketId != null) {
        board.bucketGroups.find { it.bucket.id == bucketId }?.items.orEmpty()
      } else {
        board.bucketGroups.flatMap { it.items }
      }

      candidateItems
        .filter { item ->
          // Only notify/ring alarm if no terminal action (TAKEN or SKIPPED) has been taken!
          !item.isCompleted && (targetItemKey == null || item.key == targetItemKey)
        }
        .forEach { item ->
          DoseNotificationManager.showDoseWindowNotification(
            context = context,
            item = item,
            stage = stage,
            settings = s.settings,
          )
        }
      return
    }

    val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
    val medicationId = intent.getStringExtra(EXTRA_MEDICATION_ID) ?: return
    val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID)
    val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID)
    val dateIso = intent.getStringExtra(EXTRA_DATE_ISO) ?: LocalDate.now().toString()
    val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 1.0)
    val unitName = intent.getStringExtra(EXTRA_UNIT)
    val unit = unitName?.let { runCatching { DoseUnit.valueOf(it) }.getOrNull() }
    val siteUsed = intent.getStringExtra(EXTRA_SITE_USED)
    val existingLogId = intent.getStringExtra(EXTRA_EXISTING_LOG_ID)

    when (action) {
      ACTION_TAKE -> {
        val logged = repo.logDose(
          medicationId = medicationId,
          scheduleId = scheduleId,
          bucketId = bucketId,
          scheduledDateIso = dateIso,
          status = DoseStatus.TAKEN,
          amountTaken = amount,
          amountUnit = unit,
          siteUsed = siteUsed,
          existingLogId = existingLogId,
        )
        val siteNote = logged?.siteUsed?.let { " ($it)" } ?: ""
        Toast.makeText(context, "Logged dose taken$siteNote", Toast.LENGTH_SHORT).show()
      }

      ACTION_SNOOZE -> {
        val snoozeMins = repo.snapshot.value.settings.snoozeMinutes
        val snoozeUntil = System.currentTimeMillis() + snoozeMins.toLong() * 60L * 1000L
        repo.logDose(
          medicationId = medicationId,
          scheduleId = scheduleId,
          bucketId = bucketId,
          scheduledDateIso = dateIso,
          status = DoseStatus.SNOOZED,
          amountTaken = amount,
          amountUnit = unit,
          siteUsed = siteUsed,
          snoozedUntilMillis = snoozeUntil,
          existingLogId = existingLogId,
        )
        Toast.makeText(context, "Snoozed for $snoozeMins minutes", Toast.LENGTH_SHORT).show()
      }

      ACTION_SKIP -> {
        repo.logDose(
          medicationId = medicationId,
          scheduleId = scheduleId,
          bucketId = bucketId,
          scheduledDateIso = dateIso,
          status = DoseStatus.SKIPPED,
          amountTaken = amount,
          amountUnit = unit,
          siteUsed = null,
          existingLogId = existingLogId,
        )
        Toast.makeText(context, "Dose marked skipped", Toast.LENGTH_SHORT).show()
      }
    }

    if (notificationId != 0) {
      NotificationManagerCompat.from(context).cancel(notificationId)
    }
  }

  companion object {
    const val ACTION_TAKE = "com.exaclast.regimen.action.TAKE_DOSE"
    const val ACTION_SNOOZE = "com.exaclast.regimen.action.SNOOZE_DOSE"
    const val ACTION_SKIP = "com.exaclast.regimen.action.SKIP_DOSE"
    const val ACTION_TRIGGER_WINDOW_START = "com.exaclast.regimen.action.TRIGGER_WINDOW_START"
    const val ACTION_TRIGGER_WINDOW_END_ALARM = "com.exaclast.regimen.action.TRIGGER_WINDOW_END_ALARM"

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
  }
}
