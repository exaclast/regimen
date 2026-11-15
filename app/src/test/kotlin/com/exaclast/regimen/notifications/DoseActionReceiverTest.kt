package com.exaclast.regimen.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoseActionReceiverTest {

  private lateinit var app: Application
  private lateinit var repo: RegimenRepository
  private lateinit var receiver: DoseActionReceiver
  private val originalDispatcher = DoseActionReceiver.backgroundDispatcher

  @Before
  fun setUp() {
    RegimenRepository.resetForTests()
    DoseNotificationManager.resetForTests()
    DoseActionReceiver.backgroundDispatcher = Dispatchers.Unconfined
    app = ApplicationProvider.getApplicationContext()
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    repo = RegimenRepository.getInstance(app)
    receiver = DoseActionReceiver()
  }

  @After
  fun tearDown() {
    DoseActionReceiver.backgroundDispatcher = originalDispatcher
    RegimenRepository.resetForTests()
    DoseNotificationManager.resetForTests()
  }

  @Test
  fun actionTake_singleDose_logsTakenWithSuggestedSiteAndShowsToast() {
    val todayIso = LocalDate.now().toString()
    val med = Medication(
      id = "med_inj",
      formulaName = "B12",
      strength = "1000 mcg/mL",
      form = MedicationForm.INJECTABLE_VIAL,
      siteMode = SiteMode.ROTATE,
      sites = listOf("Left Glute", "Right Glute"),
    )
    val sched = MedicationSchedule(
      id = "sched_inj",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      doseAmount = 1.0,
      doseUnit = DoseUnit.ML,
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(med, listOf(sched))

    val itemKey = "${sched.id}_${TimeOfDayBucket.BUCKET_MORNING}_$todayIso"
    val intent = Intent(DoseActionReceiver.ACTION_TAKE).apply {
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_MORNING)
      putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
      putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, arrayOf(itemKey))
    }

    receiver.onReceive(app, intent)
    ShadowLooper.idleMainLooper()

    val logs = repo.snapshot.value.doseLogs
    assertEquals(1, logs.size)
    assertEquals(DoseStatus.TAKEN, logs.single().status)
    assertEquals("Left Glute", logs.single().siteUsed)
    assertNotNull(ShadowToast.getTextOfLatestToast())
  }

  @Test
  fun actionTake_multiItemNotification_logsReadyAndSkipsPacingBlocked() {
    val todayIso = LocalDate.now().toString()
    val readyMed = Medication(id = "med_ready", formulaName = "Med A", strength = "10 mg")
    val blockedMed = Medication(
      id = "med_blocked",
      formulaName = "Med B",
      strength = "20 mg",
      minIntervalMinutes = 480,
    )
    val readySched = MedicationSchedule(
      id = "s_ready",
      medicationId = readyMed.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    val blockedSched = MedicationSchedule(
      id = "s_blocked",
      medicationId = blockedMed.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(readyMed, listOf(readySched))
    repo.saveMedication(blockedMed, listOf(blockedSched))
    // Recent taken dose blocks Med B via minIntervalMinutes.
    repo.updateDoseLog(
      DoseLog(
        id = "recent_b",
        medicationId = blockedMed.id,
        scheduleId = null,
        bucketId = null,
        scheduledDateIso = todayIso,
        actionTimestampMillis = System.currentTimeMillis() - 60_000L,
        status = DoseStatus.TAKEN,
        amountTaken = 1.0,
        amountUnit = DoseUnit.TABLET,
      ),
    )

    val readyKey = "${readySched.id}_${TimeOfDayBucket.BUCKET_MORNING}_$todayIso"
    val blockedKey = "${blockedSched.id}_${TimeOfDayBucket.BUCKET_MORNING}_$todayIso"
    val intent = Intent(DoseActionReceiver.ACTION_TAKE).apply {
      putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_MORNING)
      putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
      putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, arrayOf(readyKey, blockedKey))
    }

    receiver.onReceive(app, intent)
    ShadowLooper.idleMainLooper()

    val logs = repo.snapshot.value.doseLogs
    assertTrue(logs.any { it.medicationId == readyMed.id && it.scheduleId == readySched.id && it.status == DoseStatus.TAKEN })
    assertTrue(logs.none { it.medicationId == blockedMed.id && it.scheduleId == blockedSched.id })
    val toast = ShadowToast.getTextOfLatestToast()
    assertNotNull(toast)
    assertTrue(toast.contains("Med B"))
  }

  @Test
  fun actionSnoozeAndDismissed_logSnoozedWithDismissedStayingQuiet() {
    val todayIso = LocalDate.now().toString()
    val med = Medication(id = "med_s", formulaName = "Med S", strength = "5 mg")
    val sched = MedicationSchedule(
      id = "sched_s",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_EVENING),
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(med, listOf(sched))
    val key = "${sched.id}_${TimeOfDayBucket.BUCKET_EVENING}_$todayIso"

    // Swiping away (ACTION_DISMISSED) snoozes silently without showing a toast.
    ShadowToast.reset()
    receiver.onReceive(
      app,
      Intent(DoseActionReceiver.ACTION_DISMISSED).apply {
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_EVENING)
        putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
        putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, arrayOf(key))
      },
    )
    ShadowLooper.idleMainLooper()

    val snoozed = repo.snapshot.value.doseLogs.single()
    assertEquals(DoseStatus.SNOOZED, snoozed.status)
    assertTrue((snoozed.snoozedUntilMillis ?: 0L) > System.currentTimeMillis())
    assertNull(ShadowToast.getTextOfLatestToast())

    // Explicit ACTION_SNOOZE shows a toast.
    receiver.onReceive(
      app,
      Intent(DoseActionReceiver.ACTION_SNOOZE).apply {
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_EVENING)
        putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
        putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, arrayOf(key))
      },
    )
    ShadowLooper.idleMainLooper()
    assertNotNull(ShadowToast.getTextOfLatestToast())
  }

  @Test
  fun actionSkip_logsSkippedAndUpdatesSnapshot() {
    val todayIso = LocalDate.now().toString()
    val med = Medication(id = "med_skip", formulaName = "Med Skip", strength = "5 mg")
    val sched = MedicationSchedule(
      id = "sched_skip",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_BEDTIME),
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(med, listOf(sched))
    val key = "${sched.id}_${TimeOfDayBucket.BUCKET_BEDTIME}_$todayIso"

    receiver.onReceive(
      app,
      Intent(DoseActionReceiver.ACTION_SKIP).apply {
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_BEDTIME)
        putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
        putExtra(DoseActionReceiver.EXTRA_ITEM_KEYS, arrayOf(key))
      },
    )
    ShadowLooper.idleMainLooper()

    val log = repo.snapshot.value.doseLogs.single()
    assertEquals(DoseStatus.SKIPPED, log.status)
    assertNotNull(ShadowToast.getTextOfLatestToast())
  }

  @Test
  fun actionTriggerWindowStart_postsNotificationForActiveWindow() {
    val now = LocalTime.now()
    val start = now.minusHours(1)
    val end = now.plusHours(1)
    val activeBucket = TimeOfDayBucket(
      id = "bucket_active_now",
      name = "Right Now",
      hour = start.hour,
      minute = start.minute,
      endHour = end.hour,
      endMinute = end.minute,
    )
    repo.saveBucket(activeBucket)
    repo.saveSettings(AppSettings(notificationsEnabled = true, alarmsEnabled = true))

    val occurrenceDateIso = DoseAlarmTiming.currentOccurrenceDateIso(
      activeBucket,
      System.currentTimeMillis(),
      LocalDate.now(),
    )
    val med = Medication(id = "med_notif", formulaName = "Atorvastatin", strength = "20 mg")
    val sched = MedicationSchedule(
      id = "sched_notif",
      medicationId = med.id,
      bucketIds = listOf(activeBucket.id),
      startDateIso = occurrenceDateIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(med, listOf(sched))

    // Clear any reconciled notifications first so we verify ACTION_TRIGGER_WINDOW_START posts it.
    val nm = app.getSystemService(NotificationManager::class.java)
    nm.cancelAll()

    receiver.onReceive(
      app,
      Intent(DoseActionReceiver.ACTION_TRIGGER_WINDOW_START).apply {
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, activeBucket.id)
        putExtra(DoseActionReceiver.EXTRA_DATE_ISO, occurrenceDateIso)
      },
    )

    val posted = Shadows.shadowOf(nm).allNotifications
    assertTrue(posted.isNotEmpty())
  }

  @Test
  fun actionTriggerSnoozeExpired_postsNotificationWhenSnoozeElapses() {
    val todayIso = LocalDate.now().toString()
    val med = Medication(id = "med_snz_exp", formulaName = "Metoprolol", strength = "25 mg")
    val sched = MedicationSchedule(
      id = "sched_snz_exp",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      startDateIso = todayIso,
      createdAtEpochMillis = 1L,
    )
    repo.saveMedication(med, listOf(sched))
    repo.updateDoseLog(
      DoseLog(
        id = "log_snz_exp",
        medicationId = med.id,
        scheduleId = sched.id,
        bucketId = TimeOfDayBucket.BUCKET_MORNING,
        scheduledDateIso = todayIso,
        status = DoseStatus.SNOOZED,
        snoozedUntilMillis = System.currentTimeMillis() - 1_000L,
        amountTaken = 1.0,
        amountUnit = DoseUnit.TABLET,
      ),
    )

    val nm = app.getSystemService(NotificationManager::class.java)
    nm.cancelAll()

    val itemKey = "${sched.id}_${TimeOfDayBucket.BUCKET_MORNING}_$todayIso"
    receiver.onReceive(
      app,
      Intent(DoseActionReceiver.ACTION_TRIGGER_SNOOZE_EXPIRED).apply {
        putExtra(DoseActionReceiver.EXTRA_BUCKET_ID, TimeOfDayBucket.BUCKET_MORNING)
        putExtra(DoseActionReceiver.EXTRA_DATE_ISO, todayIso)
        putExtra(DoseActionReceiver.EXTRA_ITEM_KEY, itemKey)
      },
    )

    assertTrue(Shadows.shadowOf(nm).allNotifications.isNotEmpty())
  }

  @Test
  fun actionBootCompleted_refreshesAlarms() {
    val med = Medication(id = "med_boot", formulaName = "Levothyroxine", strength = "50 mcg")
    val sched = MedicationSchedule(
      id = "sched_boot",
      medicationId = med.id,
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
    )
    repo.saveMedication(med, listOf(sched))

    val alarmManager = app.getSystemService(AlarmManager::class.java)
    val shadowAlarms = Shadows.shadowOf(alarmManager)
    shadowAlarms.scheduledAlarms.clear()

    receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

    assertTrue(shadowAlarms.scheduledAlarms.isNotEmpty())
  }
}
