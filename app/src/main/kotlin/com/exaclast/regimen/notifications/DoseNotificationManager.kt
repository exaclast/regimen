package com.exaclast.regimen.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exaclast.regimen.MainActivity
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.TimeOfDayBucket
import java.util.Calendar

enum class DoseNotificationStage {
  WINDOW_START_PERSISTENT,
  WINDOW_END_ALARM,
}

object DoseNotificationManager {

  const val CHANNEL_WINDOW_PERSISTENT = "regimen_window_persistent"
  const val CHANNEL_WINDOW_ALARM = "regimen_window_alarm"

  fun ensureChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val persistentChannel = NotificationChannel(
      CHANNEL_WINDOW_PERSISTENT,
      "Active Dose Window (Persistent)",
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
      description = "Ongoing notification pinned at the start of a dose window with 1-tap Take, Snooze, and Skip actions"
      setShowBadge(true)
    }

    val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val alarmAudioAttrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

    val alarmChannel = NotificationChannel(
      CHANNEL_WINDOW_ALARM,
      "End-of-Window Dose Alarm",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Ringing alarm triggered at the end of a dose window if no action was taken"
      enableVibration(true)
      setBypassDnd(true)
      setSound(alarmSoundUri, alarmAudioAttrs)
    }

    manager.createNotificationChannel(persistentChannel)
    manager.createNotificationChannel(alarmChannel)
  }

  fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun cancelNotificationForItem(context: Context, itemKey: String) {
    NotificationManagerCompat.from(context).cancel(itemKey.hashCode())
  }

  /**
   * Posts either:
   * - WINDOW_START_PERSISTENT: An ongoing/persistent notification at the start of the window
   * - WINDOW_END_ALARM: An insistent ringing alarm notification at the end of the window if unlogged
   */
  fun showDoseWindowNotification(
    context: Context,
    item: ScheduledDoseItem,
    stage: DoseNotificationStage = DoseNotificationStage.WINDOW_START_PERSISTENT,
  ): Boolean {
    ensureChannels(context)
    if (!hasNotificationPermission(context)) {
      return false
    }

    val notificationId = item.key.hashCode()
    val med = item.medication
    val isAlarm = stage == DoseNotificationStage.WINDOW_END_ALARM

    val openIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      context,
      notificationId,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val title = if (isAlarm) {
      "⏰ Window Ending: ${med.displayTitle} (${med.strength})"
    } else {
      "Dose Window Open: ${med.displayTitle} (${med.strength})"
    }

    val summaryLines = mutableListOf<String>()
    if (isAlarm) {
      summaryLines.add("Window (${item.bucket.formattedTime()}) ended with no action logged!")
    } else {
      summaryLines.add(
        "Window: ${item.bucket.formattedTime()} • Alarm at ${item.bucket.formattedEndTime()} if unlogged"
      )
    }
    summaryLines.add("Dose: ${item.formattedDoseInstruction()} (${item.bucket.name})")

    if (!item.suggestedSite.isNullOrBlank()) {
      val prevInfo = item.lastSiteUsed?.let { " (Last: $it)" } ?: ""
      summaryLines.add("Suggested Site: ${item.suggestedSite}$prevInfo")
    }
    med.injectableDetails?.let { inj ->
      val draw = inj.formattedDrawNeedle()?.let { "Draw: $it" }
      val inject = inj.formattedInjectNeedle()?.let { "Inject: $it" }
      val needles = listOfNotNull(draw, inject).joinToString(" • ")
      if (needles.isNotEmpty()) {
        summaryLines.add(needles)
      }
    }

    val shortText = if (!item.suggestedSite.isNullOrBlank()) {
      "${item.formattedDoseInstruction()} • Site: ${item.suggestedSite} (${item.bucket.formattedTime()})"
    } else {
      "${item.formattedDoseInstruction()} • Window: ${item.bucket.formattedTime()}"
    }

    val takeLabel = if (!item.suggestedSite.isNullOrBlank()) {
      "Take (${item.suggestedSite})"
    } else {
      "Take Dose"
    }

    val takePendingIntent = buildActionPendingIntent(
      context = context,
      action = DoseActionReceiver.ACTION_TAKE,
      item = item,
      notificationId = notificationId,
      requestCodeOffset = 1,
    )
    val snoozePendingIntent = buildActionPendingIntent(
      context = context,
      action = DoseActionReceiver.ACTION_SNOOZE,
      item = item,
      notificationId = notificationId,
      requestCodeOffset = 2,
    )
    val skipPendingIntent = buildActionPendingIntent(
      context = context,
      action = DoseActionReceiver.ACTION_SKIP,
      item = item,
      notificationId = notificationId,
      requestCodeOffset = 3,
    )

    val channelId = if (isAlarm) CHANNEL_WINDOW_ALARM else CHANNEL_WINDOW_PERSISTENT
    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(
        if (isAlarm) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_popup_reminder
      )
      .setContentTitle(title)
      .setContentText(shortText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(summaryLines.joinToString("\n")))
      .setPriority(
        if (isAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT
      )
      .setCategory(
        if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER
      )
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setAutoCancel(false)
      .addAction(android.R.drawable.checkbox_on_background, takeLabel, takePendingIntent)
      .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 30m", snoozePendingIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skipPendingIntent)

    if (isAlarm) {
      val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      builder.setSound(alarmSoundUri)
      builder.setFullScreenIntent(contentPendingIntent, true)
    }

    val notification = builder.build()
    if (isAlarm) {
      // Continuously ring the alarm sound until the user takes action or dismisses
      notification.flags = notification.flags or Notification.FLAG_INSISTENT
    }

    return runCatching {
      NotificationManagerCompat.from(context).notify(notificationId, notification)
      true
    }.getOrDefault(false)
  }

  fun scheduleSnoozeAlarm(context: Context, itemKey: String, bucketId: String?, triggerAtMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, DoseActionReceiver::class.java).apply {
      action = DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM
      putExtra(DoseActionReceiver.EXTRA_ITEM_KEY, itemKey)
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucketId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      itemKey.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    runCatching {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
  }

  /**
   * Schedules both:
   * 1. Window Start (posts ongoing persistent notification with Take/Snooze/Skip actions)
   * 2. Window End (rings insistent alarm if no action was taken during the window)
   */
  fun scheduleDailyBucketAlarms(context: Context, buckets: List<TimeOfDayBucket>) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    buckets.forEach { bucket ->
      val startIntent = Intent(context, DoseActionReceiver::class.java).apply {
        action = DoseActionReceiver.ACTION_TRIGGER_WINDOW_START
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucket.id)
      }
      val startPendingIntent = PendingIntent.getBroadcast(
        context,
        bucket.id.hashCode() * 10 + 1,
        startIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

      val endIntent = Intent(context, DoseActionReceiver::class.java).apply {
        action = DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucket.id)
      }
      val endPendingIntent = PendingIntent.getBroadcast(
        context,
        bucket.id.hashCode() * 10 + 2,
        endIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

      if (!bucket.notificationsEnabled) {
        alarmManager.cancel(startPendingIntent)
        alarmManager.cancel(endPendingIntent)
        return@forEach
      }

      val startCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, bucket.startHour)
        set(Calendar.MINUTE, bucket.startMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) {
          add(Calendar.DAY_OF_YEAR, 1)
        }
      }
      runCatching {
        alarmManager.setInexactRepeating(
          AlarmManager.RTC_WAKEUP,
          startCal.timeInMillis,
          AlarmManager.INTERVAL_DAY,
          startPendingIntent,
        )
      }

      if (bucket.alarmAtWindowEnd) {
        val endCal = Calendar.getInstance().apply {
          set(Calendar.HOUR_OF_DAY, bucket.endHour)
          set(Calendar.MINUTE, bucket.endMinute)
          set(Calendar.SECOND, 0)
          set(Calendar.MILLISECOND, 0)
          if (timeInMillis <= System.currentTimeMillis()) {
            add(Calendar.DAY_OF_YEAR, 1)
          }
        }
        runCatching {
          alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            endCal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            endPendingIntent,
          )
        }
      } else {
        alarmManager.cancel(endPendingIntent)
      }
    }
  }

  private fun buildActionPendingIntent(
    context: Context,
    action: String,
    item: ScheduledDoseItem,
    notificationId: Int,
    requestCodeOffset: Int,
  ): PendingIntent {
    val intent = Intent(context, DoseActionReceiver::class.java).apply {
      this.action = action
      putExtra(DoseActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
      putExtra(DoseActionReceiver.EXTRA_MEDICATION_ID, item.medication.id)
      putExtra(DoseActionReceiver.EXTRA_SCHEDULE_ID, item.schedule.id)
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, item.bucket.id)
      putExtra(DoseActionReceiver.EXTRA_DATE_ISO, item.dateIso)
      putExtra(DoseActionReceiver.EXTRA_AMOUNT, item.schedule.doseAmount)
      putExtra(DoseActionReceiver.EXTRA_UNIT, item.schedule.doseUnit.name)
      putExtra(DoseActionReceiver.EXTRA_SITE_USED, item.suggestedSite)
      putExtra(DoseActionReceiver.EXTRA_EXISTING_LOG_ID, item.existingLog?.id)
    }
    return PendingIntent.getBroadcast(
      context,
      notificationId * 10 + requestCodeOffset,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
