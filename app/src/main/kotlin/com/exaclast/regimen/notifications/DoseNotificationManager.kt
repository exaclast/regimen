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
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exaclast.regimen.MainActivity
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DailyDoseBoard
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ClockPreferences
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate

enum class DoseNotificationStage {
  WINDOW_START_PERSISTENT,
  WINDOW_END_ALARM,
}

/**
 * Notification and alarm engine.
 *
 * Per dose window (bucket) at most three alarms are armed, each a single PendingIntent slot that
 * re-arming replaces:
 * - START: the next window opening that still has doses to take (looks ahead past days with
 *   nothing scheduled). Posts the persistent notification.
 * - END: only while the current window is open with unlogged doses. Rings the alarm.
 * - NUDGE: repeating reminders on a fixed grid from window start while doses remain unlogged.
 * Snoozes get their own per-dose alarm.
 *
 * [refresh] is the single, idempotent entry point after any data, settings, clock or permission
 * change: it re-arms every alarm and reconciles posted notifications with the data. It never makes
 * noise; only alarm receivers alert, and each alarm event alerts once.
 */
object DoseNotificationManager {

  const val CHANNEL_WINDOW_PERSISTENT = "regimen_window_persistent"
  const val CHANNEL_WINDOW_ALARM = "regimen_window_alarm"

  /** One notification per window occurrence, tagged `window:<bucketId>:<dateIso>`. */
  private const val WINDOW_TAG_PREFIX = "window:"

  /** Per-dose tags used by an earlier version; cleaned up on reconcile. */
  private const val LEGACY_DOSE_TAG_PREFIX = "dose:"

  /** Notification extra: the item keys a window notification lists (and its actions act on). */
  private const val EXTRA_NOTIFICATION_ITEM_KEYS = "regimen.item_keys"

  /** Notification extra: the listed doses that Take applies to (the rest are over a pacing limit). */
  private const val EXTRA_NOTIFICATION_READY_KEYS = "regimen.ready_keys"

  private const val SLOT_START = 1
  private const val SLOT_END = 2
  private const val SLOT_NUDGE = 3

  /** Days to search for a window's next dose before arming a re-scan wake-up instead. */
  private const val NEXT_START_LOOKAHEAD_DAYS = 31L

  @Volatile
  private var channelsCreated = false

  fun ensureChannels(context: Context) {
    if (channelsCreated) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return

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

    // USAGE_ALARM + CATEGORY_ALARM is what lets this through Do Not Disturb when alarms are allowed;
    // NotificationChannel.setBypassDnd is ignored for third-party apps.
    val alarmChannel = NotificationChannel(
      CHANNEL_WINDOW_ALARM,
      "End-of-Window Dose Alarm",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Ringing alarm triggered at the exact end of a dose window if no action was taken"
      enableVibration(true)
      setSound(alarmSoundUri, alarmAudioAttrs)
    }

    manager.createNotificationChannels(listOf(persistentChannel, alarmChannel))
    channelsCreated = true
  }

  // region Permissions

  fun hasNotificationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun canScheduleExactAlarms(context: Context): Boolean {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
  }

  /** Full-screen intents are restricted; without this the end alarm is a heads-up only. */
  fun canUseFullScreenIntent(context: Context): Boolean {
    return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
  }

  fun openFullScreenIntentSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
      data = Uri.parse("package:${context.packageName}")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
  }

  fun openExactAlarmSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
      data = Uri.parse("package:${context.packageName}")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
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

  // endregion

  // region Refresh (alarms + notification reconciliation)

  /**
   * Re-arms every window alarm and reconciles posted notifications with [snapshot]. Safe to call
   * on every data change; it is silent and shares one set of computed boards between both passes.
   *
   * A receiver about to alert for a window passes it as [alertingBucketId]: that window's
   * notifications are kept but not silently re-posted first, because Android rate-limits
   * notification updates and could drop the alerting one.
   */
  fun refresh(context: Context, snapshot: RegimenSnapshot, alertingBucketId: String? = null) {
    val boards = BoardCache(snapshot)
    val nowMillis = System.currentTimeMillis()
    val today = LocalDate.now()
    scheduleBucketAlarms(context, snapshot, boards, nowMillis, today)
    armPendingSnoozes(context, snapshot, nowMillis)
    reconcileNotifications(context, snapshot, boards, nowMillis, today, alertingBucketId)
  }

  private fun armPendingSnoozes(context: Context, snapshot: RegimenSnapshot, nowMillis: Long) {
    if (!snapshot.settings.notificationsEnabled) return
    DoseAlarmTiming.pendingSnoozes(snapshot.doseLogs, nowMillis).forEach { snooze ->
      scheduleSnoozeAlarm(context, snooze.itemKey, snooze.bucketId, snooze.dateIso, snooze.triggerAtMillis)
    }
  }

  /** A dose that still needs the user: not taken/skipped and not snoozed beyond [nowMillis]. */
  fun isActionable(item: ScheduledDoseItem, nowMillis: Long): Boolean {
    if (item.isCompleted) return false
    val log = item.existingLog ?: return true
    return !(log.status == DoseStatus.SNOOZED && (log.snoozedUntilMillis ?: 0L) > nowMillis)
  }

  /** All scheduled doses for [bucketId] on [dateIso], whatever their state. */
  fun itemsFor(snapshot: RegimenSnapshot, bucketId: String, dateIso: String): List<ScheduledDoseItem> =
    BoardCache(snapshot).items(dateIso, bucketId)

  /** Actionable doses for [bucketId] on [dateIso]. */
  fun actionableItems(
    snapshot: RegimenSnapshot,
    bucketId: String,
    dateIso: String,
    nowMillis: Long = System.currentTimeMillis(),
  ): List<ScheduledDoseItem> = itemsFor(snapshot, bucketId, dateIso).filter { isActionable(it, nowMillis) }

  private fun scheduleBucketAlarms(
    context: Context,
    snapshot: RegimenSnapshot,
    boards: BoardCache,
    nowMillis: Long,
    today: LocalDate,
  ) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val settings = snapshot.settings

    snapshot.buckets.forEach { bucket ->
      val hasSchedules = settings.notificationsEnabled &&
        bucket.notificationsEnabled &&
        snapshot.schedules.any {
          it.deletedAtEpochMillis == null &&
            it.frequencyType != ScheduleFrequencyType.AS_NEEDED &&
            it.bucketIds.contains(bucket.id)
        }
      if (!hasSchedules) {
        cancelBucketAlarms(context, bucket.id)
        return@forEach
      }

      // 1. The window open right now (yesterday's, for an overnight window after midnight).
      val currentDateIso = listOf(today.minusDays(1), today)
        .map { it.toString() }
        .firstOrNull { DoseAlarmTiming.isWithinWindow(bucket, it, nowMillis) }
      val currentPending = currentDateIso
        ?.let { date -> boards.items(date, bucket.id).filter { !it.isCompleted } }
        .orEmpty()

      if (currentDateIso != null && currentPending.isNotEmpty()) {
        val windowStart = DoseAlarmTiming.windowStartMillis(bucket, currentDateIso)
        val windowEnd = DoseAlarmTiming.windowEndMillis(bucket, currentDateIso)

        if (settings.alarmsEnabled && bucket.alarmAtWindowEnd && windowEnd != null) {
          armBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM,
            SLOT_END, bucket.id, currentDateIso, windowEnd, useAlarmClockPriority = true)
        } else {
          cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM, SLOT_END, bucket.id)
        }

        val nextNudge = DoseAlarmTiming.nextNudgeMillis(
          nowMillis = nowMillis,
          repeatIntervalMinutes = settings.reminderRepeatMinutes,
          windowEndMillis = windowEnd,
          windowStartMillis = windowStart,
        )
        if (nextNudge != null) {
          armBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_NUDGE,
            SLOT_NUDGE, bucket.id, currentDateIso, nextNudge, useAlarmClockPriority = false)
        } else {
          cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_NUDGE, SLOT_NUDGE, bucket.id)
        }
      } else {
        cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM, SLOT_END, bucket.id)
        cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_NUDGE, SLOT_NUDGE, bucket.id)
      }

      // 2. The next window opening that still has doses to take. Sparse schedules (every N days,
      // specific dates) may skip days, so look ahead rather than only at today/tomorrow.
      var nextDateIso: String? = null
      var nextStartMillis: Long? = null
      for (offset in 0..NEXT_START_LOOKAHEAD_DAYS) {
        val date = today.plusDays(offset).toString()
        val start = DoseAlarmTiming.windowStartMillis(bucket, date) ?: continue
        if (start <= nowMillis) continue
        if (boards.items(date, bucket.id).any { !it.isCompleted }) {
          nextDateIso = date
          nextStartMillis = start
          break
        }
      }
      if (nextStartMillis == null) {
        // Nothing within the horizon: wake then to look again (the receiver posts nothing if empty).
        val rescanDate = today.plusDays(NEXT_START_LOOKAHEAD_DAYS + 1).toString()
        nextDateIso = rescanDate
        nextStartMillis = DoseAlarmTiming.windowStartMillis(bucket, rescanDate)
      }
      if (nextDateIso != null && nextStartMillis != null) {
        armBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_START,
          SLOT_START, bucket.id, nextDateIso, nextStartMillis, useAlarmClockPriority = false)
      }
    }
  }

  /**
   * Makes the shade match the data, one notification per window occurrence: keeps each window's
   * notification listing exactly its actionable doses (silently re-posting when that set changes),
   * pins open windows that have none yet, keeps an overdue window's notification until the same
   * window opens again, and removes everything else — doses logged elsewhere, deleted medications
   * or schedules, disabled windows.
   */
  private fun reconcileNotifications(
    context: Context,
    snapshot: RegimenSnapshot,
    boards: BoardCache,
    nowMillis: Long,
    today: LocalDate,
    alertingBucketId: String?,
  ) {
    if (!snapshot.settings.notificationsEnabled) {
      cancelAllNotifications(context)
      return
    }
    if (!hasNotificationPermission(context)) return

    val active = activeDoseNotifications(context)
    val keepTags = HashSet<String>()
    snapshot.buckets.filter { it.notificationsEnabled }.forEach { bucket ->
      val dateIso = DoseAlarmTiming.currentOccurrenceDateIso(bucket, nowMillis, today)
      val actionable = boards.items(dateIso, bucket.id).filter { isActionable(it, nowMillis) }
      if (actionable.isEmpty()) return@forEach
      val tag = windowTag(bucket.id, dateIso)
      keepTags += tag
      if (bucket.id == alertingBucketId) return@forEach // the receiver posts this one, with sound

      val existing = active[tag]
      val keys = actionable.map { it.key }
      if (existing != null) {
        val shownKeys = existing.notification.extras.getStringArray(EXTRA_NOTIFICATION_ITEM_KEYS)?.toList()
        val shownReady = existing.notification.extras.getStringArray(EXTRA_NOTIFICATION_READY_KEYS)?.toList()
        val ready = actionable.filter { it.pacing.canTakeNow }.map { it.key }
        if (shownKeys != keys || shownReady != ready) {
          val stage = if (existing.notification.channelId == CHANNEL_WINDOW_ALARM) {
            DoseNotificationStage.WINDOW_END_ALARM
          } else {
            DoseNotificationStage.WINDOW_START_PERSISTENT
          }
          postWindowNotification(context, bucket, dateIso, actionable, stage, snapshot.settings, alert = false)
        }
      } else if (DoseAlarmTiming.isWithinWindow(bucket, dateIso, nowMillis)) {
        postWindowNotification(
          context, bucket, dateIso, actionable,
          DoseNotificationStage.WINDOW_START_PERSISTENT, snapshot.settings, alert = false,
        )
      }
    }

    val compat = NotificationManagerCompat.from(context)
    active.forEach { (tag, sbn) -> if (tag !in keepTags) compat.cancel(tag, sbn.id) }
    // Untagged or per-dose notifications from older app versions.
    val ourChannels = setOf(CHANNEL_WINDOW_PERSISTENT, CHANNEL_WINDOW_ALARM)
    allActiveNotifications(context).forEach { sbn ->
      val tag = sbn.tag
      when {
        tag == null && sbn.notification.channelId in ourChannels -> compat.cancel(sbn.id)
        tag != null && tag.startsWith(LEGACY_DOSE_TAG_PREFIX) -> compat.cancel(tag, sbn.id)
      }
    }
  }

  // endregion

  // region Notifications

  private fun windowTag(bucketId: String, dateIso: String) = "$WINDOW_TAG_PREFIX$bucketId:$dateIso"

  private fun allActiveNotifications(context: Context): List<android.service.notification.StatusBarNotification> =
    runCatching {
      context.getSystemService(NotificationManager::class.java)?.activeNotifications?.toList()
    }.getOrNull().orEmpty()

  /** Posted window notifications by tag. */
  private fun activeDoseNotifications(context: Context) =
    allActiveNotifications(context)
      .filter { it.tag?.startsWith(WINDOW_TAG_PREFIX) == true }
      .associateBy { it.tag!! }

  /** True when [itemKey] is already listed in its window's posted notification. */
  fun isItemShown(context: Context, bucketId: String, dateIso: String, itemKey: String): Boolean {
    val sbn = activeDoseNotifications(context)[windowTag(bucketId, dateIso)] ?: return false
    return sbn.notification.extras.getStringArray(EXTRA_NOTIFICATION_ITEM_KEYS)?.contains(itemKey) == true
  }

  /** Removes a dose's notification posted by an older app version (one notification per dose). */
  fun cancelNotificationForItem(context: Context, itemKey: String) {
    val compat = NotificationManagerCompat.from(context)
    compat.cancel(LEGACY_DOSE_TAG_PREFIX + itemKey, itemKey.hashCode())
    compat.cancel(itemKey.hashCode())
  }

  fun cancelAllNotifications(context: Context) {
    runCatching {
      NotificationManagerCompat.from(context).cancelAll()
    }
  }

  /**
   * Stops the insistent sound of a window's alarm notification while keeping it in the shade (the
   * alarm screen plays its own, user-silenceable audio). Cancelling is the only reliable way to
   * stop an insistent sound; the notification is re-posted silently right away.
   */
  fun silenceAlarmNotifications(context: Context, snapshot: RegimenSnapshot, bucketId: String, dateIso: String) {
    val bucket = snapshot.buckets.find { it.id == bucketId } ?: return
    val tag = windowTag(bucketId, dateIso)
    NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
    postWindowNotification(
      context, bucket, dateIso, actionableItems(snapshot, bucketId, dateIso),
      DoseNotificationStage.WINDOW_END_ALARM, snapshot.settings, alert = false,
    )
  }

  /**
   * Posts (or replaces) the single notification for [bucket]'s window on [dateIso], listing
   * [items]; cancels it when there are none.
   *
   * - WINDOW_START_PERSISTENT: "Morning dose" / "Morning medications", pinned while the window is open.
   * - WINDOW_END_ALARM: "… overdue". With [alert] it rings insistently and carries a full-screen
   *   intent to the alarm screen.
   *
   * Actions apply to exactly the listed doses. A dose over its pacing limit says so and Take leaves
   * it out; logging it anyway happens in the app, which explains the limit first. Swiping the
   * notification away snoozes them for the snooze length; left alone, repeat reminders re-alert.
   * With [alert] false the post is silent.
   */
  fun postWindowNotification(
    context: Context,
    bucket: TimeOfDayBucket,
    dateIso: String,
    items: List<ScheduledDoseItem>,
    stage: DoseNotificationStage,
    settings: AppSettings,
    alert: Boolean = true,
  ): Boolean {
    val tag = windowTag(bucket.id, dateIso)
    val notificationId = tag.hashCode()
    if (items.isEmpty()) {
      NotificationManagerCompat.from(context).cancel(tag, notificationId)
      return false
    }
    if (!settings.notificationsEnabled) return false
    val isAlarm = stage == DoseNotificationStage.WINDOW_END_ALARM
    if (isAlarm && !settings.alarmsEnabled) return false

    ensureChannels(context)
    if (!hasNotificationPermission(context)) return false
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(context)

    val single = items.size == 1
    val baseTitle = if (single) "${bucket.name} dose" else "${bucket.name} medications"
    val title = if (isAlarm) "$baseTitle overdue" else baseTitle
    val countText = if (single) "1 dose due" else "${items.size} doses due"

    val lines = windowBodyLines(items)
    // A single limited dose leads with the limit: it's why there's no Take button.
    val collapsedText = if (single) lines.last() else items.joinToString(", ") { it.medication.primaryName }

    val contentPendingIntent = if (isAlarm) {
      alarmScreenPendingIntent(context, bucket.id, dateIso, notificationId, ring = false)
    } else {
      val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      PendingIntent.getActivity(
        context, notificationId, openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    val keys = items.map { it.key }.toTypedArray()
    val readyKeys = items.filter { it.pacing.canTakeNow }.map { it.key }.toTypedArray()
    fun action(action: String, offset: Int, actionKeys: Array<String> = keys) =
      buildActionPendingIntent(context, action, bucket.id, dateIso, actionKeys, notificationId, offset)

    // Stable timestamp (window start/end) so silent re-posts don't reshuffle the shade.
    val whenMillis = if (isAlarm) {
      DoseAlarmTiming.windowEndMillis(bucket, dateIso)
    } else {
      DoseAlarmTiming.windowStartMillis(bucket, dateIso)
    } ?: System.currentTimeMillis()

    val channelId = if (isAlarm) CHANNEL_WINDOW_ALARM else CHANNEL_WINDOW_PERSISTENT
    val category = if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER
    val icon = R.drawable.ic_notification

    // What the lock screen shows in discreet mode: the window, never the medications.
    val publicVersion = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(icon)
      .setContentTitle(title)
      .setContentText(countText)
      .setCategory(category)
      .setWhen(whenMillis)
      .build()

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(icon)
      .setContentTitle(title)
      .setContentText(collapsedText)
      .setStyle(
        NotificationCompat.InboxStyle()
          .also { style -> lines.forEach { style.addLine(it) } }
          .setSummaryText(if (single) null else countText)
      )
      .setNumber(items.size)
      .setPriority(if (isAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
      .setCategory(category)
      .setVisibility(
        if (settings.discreetNotifications) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC
      )
      .setPublicVersion(publicVersion)
      .setWhen(whenMillis)
      .setShowWhen(true)
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setAutoCancel(false)
      .setSilent(!alert)
      .addExtras(
        Bundle().apply {
          putStringArray(EXTRA_NOTIFICATION_ITEM_KEYS, keys)
          putStringArray(EXTRA_NOTIFICATION_READY_KEYS, readyKeys)
        },
      )
      // No Snooze button: swiping the notification away is the "not now" (see setDeleteIntent).
      .setDeleteIntent(action(DoseActionReceiver.ACTION_DISMISSED, 4))
    if (readyKeys.isNotEmpty()) {
      val takeLabel = when {
        readyKeys.size < keys.size -> "Take ${readyKeys.size} ready"
        single -> "Take"
        else -> "Take all"
      }
      builder.addAction(
        android.R.drawable.checkbox_on_background,
        takeLabel,
        action(DoseActionReceiver.ACTION_TAKE, 1, readyKeys),
      )
    }
    if (readyKeys.size < keys.size) {
      builder.addAction(android.R.drawable.ic_menu_view, "Open", contentPendingIntent)
    }
    // Skipping several doses from the shade (no undo there) is too easy to do by accident.
    if (single) {
      builder.addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        "Skip",
        action(DoseActionReceiver.ACTION_SKIP, 3),
      )
    }

    val ringing = isAlarm && alert
    if (ringing) {
      builder.setFullScreenIntent(alarmScreenPendingIntent(context, bucket.id, dateIso, notificationId, ring = true), true)
    }

    val notification = builder.build()
    if (ringing) {
      // Repeat the channel's alarm sound until the user acts or the alarm screen takes over.
      notification.flags = notification.flags or Notification.FLAG_INSISTENT
    }

    return runCatching {
      NotificationManagerCompat.from(context).notify(tag, notificationId, notification)
      true
    }.getOrDefault(false)
  }

  /**
   * A window notification's body. Each row is one line that gets cut off when it's long, so the dose
   * comes before the strength, and a pacing limit gets its own row.
   */
  internal fun windowBodyLines(items: List<ScheduledDoseItem>): List<String> = items.flatMap { item ->
    val med = item.medication
    val details = listOfNotNull(
      item.suggestedSite?.takeIf { med.tracksSites && it.isNotBlank() },
      med.strength.takeIf { it.isNotBlank() },
    )
    val dose = (listOf(item.formattedDoseInstruction()) + details).joinToString(" · ")
    listOfNotNull("${med.primaryName} — $dose", item.pacing.warningText()?.let { "${med.primaryName}: $it" })
  }

  /**
   * Opens the alarm screen for a window. The full-screen launch rings; a tap on the notification
   * later just shows the screen. The two differ by action, so they are distinct PendingIntents
   * under the same request code.
   */
  private fun alarmScreenPendingIntent(
    context: Context,
    bucketId: String,
    dateIso: String,
    notificationId: Int,
    ring: Boolean,
  ): PendingIntent {
    val intent = Intent(context, DoseAlarmActivity::class.java).apply {
      action = if (ring) DoseAlarmActivity.ACTION_RING else DoseAlarmActivity.ACTION_SHOW
      putExtra(DoseAlarmActivity.EXTRA_BUCKET_ID, bucketId)
      putExtra(DoseAlarmActivity.EXTRA_DATE_ISO, dateIso)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      context, notificationId, intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun buildActionPendingIntent(
    context: Context,
    action: String,
    bucketId: String,
    dateIso: String,
    itemKeys: Array<String>,
    notificationId: Int,
    requestCodeOffset: Int,
  ): PendingIntent {
    val intent = Intent(context, DoseActionReceiver::class.java).apply {
      this.action = action
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucketId)
      putExtra(DoseActionReceiver.EXTRA_DATE_ISO, dateIso)
      putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, itemKeys)
    }
    return PendingIntent.getBroadcast(
      context,
      notificationId * 10 + requestCodeOffset,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  // endregion

  // region Alarms

  fun cancelBucketAlarms(context: Context, bucketId: String) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_START, SLOT_START, bucketId)
    cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM, SLOT_END, bucketId)
    cancelBucketAlarm(context, alarmManager, DoseActionReceiver.ACTION_TRIGGER_WINDOW_NUDGE, SLOT_NUDGE, bucketId)
  }

  private fun bucketRequestCode(bucketId: String, slot: Int) = bucketId.hashCode() * 10 + slot

  private fun bucketAlarmIntent(context: Context, action: String, bucketId: String, dateIso: String?) =
    Intent(context, DoseActionReceiver::class.java).apply {
      this.action = action
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucketId)
      dateIso?.let { putExtra(DoseActionReceiver.EXTRA_DATE_ISO, it) }
    }

  private fun armBucketAlarm(
    context: Context,
    alarmManager: AlarmManager,
    action: String,
    slot: Int,
    bucketId: String,
    dateIso: String,
    triggerAtMillis: Long,
    useAlarmClockPriority: Boolean,
  ) {
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      bucketRequestCode(bucketId, slot),
      bucketAlarmIntent(context, action, bucketId, dateIso),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    scheduleExactWakeup(context, alarmManager, triggerAtMillis, pendingIntent, useAlarmClockPriority)
  }

  /** Cancels an alarm slot if one exists; never creates a PendingIntent just to cancel it. */
  private fun cancelBucketAlarm(
    context: Context,
    alarmManager: AlarmManager,
    action: String,
    slot: Int,
    bucketId: String,
  ) {
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      bucketRequestCode(bucketId, slot),
      bucketAlarmIntent(context, action, bucketId, dateIso = null),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) ?: return
    runCatching { alarmManager.cancel(pendingIntent) }
    pendingIntent.cancel()
  }

  fun cancelSnoozeAlarm(context: Context, itemKey: String, bucketId: String?) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    // PendingIntent identity is (action, requestCode, component); extras are irrelevant. Cancel the
    // current snooze action and the legacy END_ALARM-based snooze scheduled by older app versions.
    listOf(
      DoseActionReceiver.ACTION_TRIGGER_SNOOZE_EXPIRED,
      DoseActionReceiver.ACTION_TRIGGER_WINDOW_END_ALARM,
    ).forEach { snoozeAction ->
      val intent = Intent(context, DoseActionReceiver::class.java).apply {
        action = snoozeAction
        putExtra(DoseActionReceiver.EXTRA_ITEM_KEY, itemKey)
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucketId)
      }
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        itemKey.hashCode(),
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      ) ?: return@forEach
      runCatching { alarmManager.cancel(pendingIntent) }
      pendingIntent.cancel()
    }
  }

  /**
   * Schedules an exact snooze wake-up alarm using setAlarmClock / setExactAndAllowWhileIdle.
   *
   * The dose's scheduled date travels with the alarm: a snooze can expire after midnight (or
   * belong to an overnight window), so the receiver must not infer the date from the clock.
   */
  private fun scheduleSnoozeAlarm(
    context: Context,
    itemKey: String,
    bucketId: String?,
    scheduledDateIso: String,
    triggerAtMillis: Long,
  ) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    // Replace any snooze scheduled by an older app version under the legacy action.
    cancelSnoozeAlarm(context, itemKey, bucketId)
    val intent = Intent(context, DoseActionReceiver::class.java).apply {
      action = DoseActionReceiver.ACTION_TRIGGER_SNOOZE_EXPIRED
      putExtra(DoseActionReceiver.EXTRA_ITEM_KEY, itemKey)
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, bucketId)
      putExtra(DoseActionReceiver.EXTRA_DATE_ISO, scheduledDateIso)
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

  private fun scheduleExactWakeup(
    context: Context,
    alarmManager: AlarmManager,
    triggerAtMillis: Long,
    operation: PendingIntent,
    useAlarmClockPriority: Boolean,
  ) {
    if (canScheduleExactAlarms(context)) {
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

  // endregion

  /** Builds each date's board at most once per pass. */
  private class BoardCache(private val snapshot: RegimenSnapshot) {
    private val boards = HashMap<String, DailyDoseBoard>()

    fun items(dateIso: String, bucketId: String): List<ScheduledDoseItem> {
      val board = boards.getOrPut(dateIso) {
        DoseEngine.buildDailyBoard(
          dateIso = dateIso,
          medications = snapshot.medications,
          schedules = snapshot.schedules,
          buckets = snapshot.buckets,
          logs = snapshot.doseLogs,
          indications = snapshot.indications,
          providers = snapshot.providers,
        )
      }
      return board.bucketGroups.find { it.bucket.id == bucketId }?.items.orEmpty()
    }
  }
}
