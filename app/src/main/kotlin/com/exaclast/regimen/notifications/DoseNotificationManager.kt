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
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exaclast.regimen.MainActivity
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalTime
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
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Ongoing notification pinned at the exact start of a dose window with 1-tap Take, Snooze, and Skip actions"
      setShowBadge(true)
      enableVibration(true)
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
      description = "Ringing alarm triggered at the exact end of a dose window if no action was taken"
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

  fun canScheduleExactAlarms(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarmManager.canScheduleExactAlarms()
    } else {
      true
    }
  }

  fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      runCatching { context.startActivity(intent) }
    }
  }

  fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
  }

  fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
  }

  fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${context.packageName}")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
  }

  fun areCorePermissionsGranted(context: Context): Boolean {
    return hasNotificationPermission(context) && canScheduleExactAlarms(context)
  }

  fun cancelNotificationForItem(context: Context, itemKey: String) {
    NotificationManagerCompat.from(context).cancel(itemKey.hashCode())
  }

  fun cancelAllNotifications(context: Context) {
    runCatching {
      NotificationManagerCompat.from(context).cancelAll()
    }
  }

  fun cancelSnoozeAlarm(context: Context, itemKey: String, bucketId: String?) {
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
    runCatching { alarmManager.cancel(pendingIntent) }
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
    settings: AppSettings = AppSettings(),
    onlyAlertOnce: Boolean = false,
  ): Boolean {
    if (!settings.notificationsEnabled) return false
    if (stage == DoseNotificationStage.WINDOW_END_ALARM && !settings.alarmsEnabled) return false

    ensureChannels(context)
    if (!hasNotificationPermission(context)) {
      return false
    }

    val notificationId = item.key.hashCode()
    val med = item.medication
    val isAlarm = stage == DoseNotificationStage.WINDOW_END_ALARM
    val discreet = settings.discreetNotifications

    val openIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      context,
      notificationId,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val title = when {
      discreet && isAlarm -> "⏰ Scheduled Dose Window Ending"
      discreet -> "Scheduled Dose Window Open (${item.bucket.name})"
      isAlarm -> "⏰ Window Ending: ${med.displayTitle} (${med.strength})"
      else -> "Dose Window Open: ${med.displayTitle} (${med.strength})"
    }

    val summaryLines = mutableListOf<String>()
    if (discreet) {
      summaryLines.add("Window: ${item.bucket.name} (${item.bucket.formattedTime()})")
      summaryLines.add("Open Regimen to view medication details or tap an action below.")
    } else {
      if (isAlarm) {
        summaryLines.add("Window (${item.bucket.formattedTime()}) ended with no action logged!")
      } else {
        val alarmNote = if (settings.alarmsEnabled && item.bucket.alarmAtWindowEnd) {
          " • Alarm at ${item.bucket.formattedEndTime()} if unlogged"
        } else {
          ""
        }
        summaryLines.add("Window: ${item.bucket.formattedTime()}$alarmNote")
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
    }

    val shortText = when {
      discreet -> "Scheduled dose ready • Window: ${item.bucket.formattedTime()}"
      !item.suggestedSite.isNullOrBlank() ->
        "${item.formattedDoseInstruction()} • Site: ${item.suggestedSite} (${item.bucket.formattedTime()})"
      else -> "${item.formattedDoseInstruction()} • Window: ${item.bucket.formattedTime()}"
    }

    val takeLabel = if (!discreet && !item.suggestedSite.isNullOrBlank()) {
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
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(shortText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(summaryLines.joinToString("\n")))
      .setPriority(
        if (isAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH
      )
      .setCategory(
        if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER
      )
      .setVisibility(
        if (discreet) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC
      )
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setAutoCancel(false)
      .setOnlyAlertOnce(!isAlarm && onlyAlertOnce)
      .addAction(android.R.drawable.checkbox_on_background, takeLabel, takePendingIntent)
      .addAction(
        android.R.drawable.ic_lock_idle_alarm,
        "Snooze ${settings.snoozeMinutes}m",
        snoozePendingIntent,
      )
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

  /**
   * Schedules an exact snooze wake-up alarm using setAlarmClock / setExactAndAllowWhileIdle.
   */
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
    scheduleExactWakeup(
      context = context,
      alarmManager = alarmManager,
      triggerAtMillis = triggerAtMillis,
      operation = pendingIntent,
      useAlarmClockPriority = true,
    )
  }

  /**
   * Schedules exact Doze-piercing alarms for every enabled TimeOfDayBucket:
   * 1. Window Start (`ACTION_TRIGGER_WINDOW_START`): Wakes the device at the exact start minute
   *    to pin the persistent notification with 1-tap Take/Snooze/Skip actions.
   * 2. Window End (`ACTION_TRIGGER_WINDOW_END_ALARM`): Wakes the device at the exact end minute
   *    to ring the insistent alarm if no terminal action (`TAKEN` or `SKIPPED`) was logged.
   *
   * Because exact alarms on Android are single-shot, [DoseActionReceiver] automatically
   * calls [scheduleDailyBucketAlarms] whenever an alarm fires to chain the next day's window.
   */
  fun scheduleDailyBucketAlarms(
    context: Context,
    buckets: List<TimeOfDayBucket>,
    settings: AppSettings = AppSettings(),
  ) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val nowMillis = System.currentTimeMillis()

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

      if (!settings.notificationsEnabled || !bucket.notificationsEnabled) {
        runCatching {
          alarmManager.cancel(startPendingIntent)
          alarmManager.cancel(endPendingIntent)
        }
        return@forEach
      }

      val startCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, bucket.startHour)
        set(Calendar.MINUTE, bucket.startMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) {
          add(Calendar.DAY_OF_YEAR, 1)
        }
      }
      scheduleExactWakeup(
        context = context,
        alarmManager = alarmManager,
        triggerAtMillis = startCal.timeInMillis,
        operation = startPendingIntent,
        useAlarmClockPriority = true,
      )

      if (settings.alarmsEnabled && bucket.alarmAtWindowEnd) {
        val endCal = Calendar.getInstance().apply {
          timeInMillis = nowMillis
          set(Calendar.HOUR_OF_DAY, bucket.endHour)
          set(Calendar.MINUTE, bucket.endMinute)
          set(Calendar.SECOND, 0)
          set(Calendar.MILLISECOND, 0)
          if (timeInMillis <= nowMillis) {
            add(Calendar.DAY_OF_YEAR, 1)
          }
        }
        scheduleExactWakeup(
          context = context,
          alarmManager = alarmManager,
          triggerAtMillis = endCal.timeInMillis,
          operation = endPendingIntent,
          useAlarmClockPriority = true,
        )
      } else {
        runCatching { alarmManager.cancel(endPendingIntent) }
      }
    }
  }

  /**
   * Ensures that if the current wall-clock time is already inside an active dose window today,
   * any unlogged scheduled doses for that window immediately have their persistent notification
   * pinned (without waiting until tomorrow's window start).
   */
  fun syncActiveWindowNotifications(context: Context, snapshot: RegimenSnapshot) {
    if (!snapshot.settings.notificationsEnabled) {
      cancelAllNotifications(context)
      return
    }
    if (!hasNotificationPermission(context)) return
    val nowTime = LocalTime.now()
    val nowMinutes = nowTime.hour * 60 + nowTime.minute
    val nowMillis = System.currentTimeMillis()

    val board = DoseEngine.buildDailyBoard(
      dateIso = LocalDate.now().toString(),
      medications = snapshot.medications,
      schedules = snapshot.schedules,
      buckets = snapshot.buckets,
      logs = snapshot.doseLogs,
      conditions = snapshot.conditions,
      prescribers = snapshot.prescribers,
    )

    board.bucketGroups.forEach { group ->
      val bucket = group.bucket
      if (!bucket.notificationsEnabled) return@forEach

      val startMinutes = bucket.startHour * 60 + bucket.startMinute
      val endMinutes = bucket.endHour * 60 + bucket.endMinute
      val isWithinWindow = if (endMinutes >= startMinutes) {
        nowMinutes in startMinutes until endMinutes
      } else {
        // Window crosses midnight
        nowMinutes >= startMinutes || nowMinutes < endMinutes
      }

      if (isWithinWindow) {
        group.items.forEach { item ->
          val snoozedInFuture = item.existingLog?.status == DoseStatus.SNOOZED &&
            (item.existingLog.snoozedUntilMillis ?: 0L) > nowMillis
          if (!item.isCompleted && !snoozedInFuture) {
            showDoseWindowNotification(
              context = context,
              item = item,
              stage = DoseNotificationStage.WINDOW_START_PERSISTENT,
              settings = snapshot.settings,
              onlyAlertOnce = true,
            )
          }
        }
      }
    }
  }

  private fun scheduleExactWakeup(
    context: Context,
    alarmManager: AlarmManager,
    triggerAtMillis: Long,
    operation: PendingIntent,
    useAlarmClockPriority: Boolean,
  ) {
    val canExact = canScheduleExactAlarms(context)
    if (canExact) {
      if (useAlarmClockPriority) {
        val showIntent = Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
          context,
          0,
          showIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scheduled = runCatching {
          alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
            operation,
          )
          true
        }.getOrDefault(false)
        if (scheduled) return
      }

      val exactScheduled = runCatching {
        alarmManager.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          triggerAtMillis,
          operation,
        )
        true
      }.getOrDefault(false)
      if (exactScheduled) return
    }

    // Fallback if exact alarm permission was explicitly revoked by user
    runCatching {
      alarmManager.setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        triggerAtMillis,
        operation,
      )
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
