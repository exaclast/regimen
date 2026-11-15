package com.exaclast.regimen.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.domain.ScheduleVersioning
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.SharedSettings
import com.exaclast.regimen.model.SyncDevice
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.sync.BundleMerger
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegimenRepositoryTest {

  private lateinit var app: Application
  private lateinit var repo: RegimenRepository

  @Before
  fun setUp() {
    RegimenRepository.resetForTests()
    DoseNotificationManager.resetForTests()
    app = ApplicationProvider.getApplicationContext()
    repo = RegimenRepository.getInstance(app)
  }

  @After
  fun tearDown() {
    RegimenRepository.resetForTests()
    DoseNotificationManager.resetForTests()
  }

  @Test
  fun applyBundle_invalidBundleThrowsAndLeavesDbAndSettingsUntouched() {
    repo.saveSettings(AppSettings(patientName = "Alice", notificationsEnabled = false))
    val med = Medication(id = "med_keep", formulaName = "Metformin", strength = "500 mg")
    repo.saveMedication(med)

    val invalidBundle = RegimenBackupBundle(
      settings = AppSettings(patientName = "Corrupted"),
      sharedSettings = SharedSettings(patientName = "Corrupted", updatedAtEpochMillis = 999L),
      primary = PrimaryClaim(deviceId = "other", epoch = 5L, claimedAtEpochMillis = 999L),
      // Wrong format fails BackupFiles.validate before DB or prefs are touched.
      format = "wrong_format",
      medications = listOf(Medication(id = "med_bad", formulaName = "Bad", strength = "1 mg")),
    )

    var threw = false
    try {
      repo.applyBundle(invalidBundle, replace = true)
    } catch (_: BackupFiles.InvalidBackupException) {
      threw = true
    }
    assertTrue(threw)
    assertEquals("Alice", repo.snapshot.value.settings.patientName)
    assertEquals(listOf("med_keep"), repo.snapshot.value.medications.map { it.id })
    assertNull(repo.currentBundle().primary)
  }

  @Test
  fun applyBundle_transactionFailureRollsBackDbAndLeavesSettingsUntouched() {
    repo.saveSettings(AppSettings(patientName = "Alice", notificationsEnabled = false))
    repo.saveMedication(Medication(id = "med_keep", formulaName = "Metformin", strength = "500 mg"))

    // Trigger simulates an unexpected DB failure mid-transaction, after BackupFiles.validate passes.
    val helperDb = RegimenDatabase(app)
    helperDb.writableDatabase.execSQL(
      "CREATE TRIGGER fail_med_insert BEFORE INSERT ON medications BEGIN SELECT RAISE(ABORT, 'disk error'); END;",
    )
    val validBundle = RegimenBackupBundle(
      settings = AppSettings(patientName = "NewName"),
      sharedSettings = SharedSettings(patientName = "NewName", updatedAtEpochMillis = 999L),
      primary = PrimaryClaim(deviceId = "other", epoch = 5L, claimedAtEpochMillis = 999L),
      devices = listOf(SyncDevice(id = "other", name = "Other")),
      medications = listOf(Medication(id = "med_new", formulaName = "Aspirin", strength = "81 mg")),
    )

    var threw = false
    try {
      repo.applyBundle(validBundle, replace = true)
    } catch (_: Exception) {
      threw = true
    } finally {
      helperDb.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_med_insert")
      helperDb.close()
    }
    assertTrue(threw)
    // Both DB rows (clearUserRecords rolled back) and prefs (never written) stay untouched.
    assertEquals("Alice", repo.snapshot.value.settings.patientName)
    assertEquals(listOf("med_keep"), repo.snapshot.value.medications.map { it.id })
    assertNull(repo.currentBundle().primary)
  }

  @Test
  fun deleteBucketAndResetToDefault_softDeletesBucketsAndPreservesTombstones() {
    val custom = TimeOfDayBucket(id = "bucket_mid", name = "Midday", hour = 11, endHour = 13)
    repo.saveBucket(custom)
    assertTrue(repo.snapshot.value.buckets.any { it.id == custom.id })

    // Deleting a default bucket produces a tombstone in currentBundle().
    repo.deleteBucket(TimeOfDayBucket.BUCKET_AFTERNOON)
    assertFalse(repo.snapshot.value.buckets.any { it.id == TimeOfDayBucket.BUCKET_AFTERNOON })
    val deletedAfternoon = repo.currentBundle().buckets.firstOrNull { it.id == TimeOfDayBucket.BUCKET_AFTERNOON }
    assertNotNull(deletedAfternoon?.deletedAtEpochMillis)

    // Resetting defaults soft-deletes custom unused buckets and revives deleted defaults.
    repo.resetBucketsToDefault()
    assertTrue(repo.snapshot.value.buckets.any { it.id == TimeOfDayBucket.BUCKET_AFTERNOON })
    assertFalse(repo.snapshot.value.buckets.any { it.id == custom.id })
    val customTombstone = repo.currentBundle().buckets.firstOrNull { it.id == custom.id }
    assertNotNull(customTombstone?.deletedAtEpochMillis)
    val revivedAfternoon = repo.currentBundle().buckets.first { it.id == TimeOfDayBucket.BUCKET_AFTERNOON }
    assertNull(revivedAfternoon.deletedAtEpochMillis)
    assertTrue(revivedAfternoon.updatedAtEpochMillis > 0L)
  }

  @Test
  fun applyBundle_persistsPrimaryClaimAndSyncDevicesAcrossReloads() {
    val selfId = repo.deviceIdentity.deviceId
    val claim = PrimaryClaim(deviceId = selfId, epoch = 2L, claimedAtEpochMillis = 5_000L)
    val peer = SyncDevice(id = "peer_1", name = "Pixel Tablet", firstSeenEpochMillis = 1_000L, lastSyncedEpochMillis = 4_000L)
    val initialBundle = repo.currentBundle().copy(
      primary = claim,
      devices = listOf(peer, repo.deviceIdentity.asSyncDevice(lastSyncedEpochMillis = 5_000L)),
      sharedSettings = SharedSettings(patientName = "Chris", updatedAtEpochMillis = 5_000L),
    )

    repo.applyBundle(initialBundle, replace = false)

    // Simulate process restart against the same SQLite + SharedPreferences.
    RegimenRepository.resetForTests()
    val reloaded = RegimenRepository.getInstance(app)
    val roundTripped = reloaded.currentBundle()
    assertEquals(claim, roundTripped.primary)
    assertTrue(roundTripped.devices.any { it.id == "peer_1" && it.lastSyncedEpochMillis == 4_000L })
    assertEquals("Chris", roundTripped.sharedSettings.patientName)

    // Merging the identical remote bundle again must converge with localChanged == false.
    val secondMerge = BundleMerger.merge(
      base = roundTripped,
      local = roundTripped,
      remote = roundTripped,
      localDeviceId = selfId,
      nowMillis = 6_000L,
    )
    assertFalse(secondMerge.localChanged)
  }

  @Test
  fun clearAllUserDataAndResetToSampleData_preserveTombstonesAndUseRandomIds() {
    val med = Medication(id = "med_tomb", formulaName = "Lisinopril", strength = "10 mg")
    repo.saveMedication(med)
    repo.deleteMedication(med.id)
    assertTrue(repo.currentBundle().medications.any { it.id == med.id && it.deletedAtEpochMillis != null })

    repo.resetToSampleData()
    val afterDemo1 = repo.currentBundle()
    assertTrue(afterDemo1.medications.any { it.id == med.id && it.deletedAtEpochMillis != null })
    val demoIds1 = repo.snapshot.value.medications.map { it.id }.toSet()
    assertTrue(demoIds1.isNotEmpty())

    repo.clearAllUserData()
    assertTrue(repo.snapshot.value.medications.isEmpty())
    val afterClear = repo.currentBundle()
    assertTrue(afterClear.medications.any { it.id == med.id && it.deletedAtEpochMillis != null })
    assertTrue(demoIds1.all { id -> afterClear.medications.any { it.id == id && it.deletedAtEpochMillis != null } })

    repo.resetToSampleData()
    val demoIds2 = repo.snapshot.value.medications.map { it.id }.toSet()
    // Fixed demo IDs are gone: loading demo data twice creates fresh UUIDs.
    assertTrue((demoIds1 intersect demoIds2).isEmpty())
  }

  @Test
  fun logDosesAndRestoreAndDeleteDoseLogs_workAtomically() {
    val med1 = Medication(id = "m1", formulaName = "Drug 1", strength = "10 mg")
    val med2 = Medication(id = "m2", formulaName = "Drug 2", strength = "20 mg")
    val sched1 = MedicationSchedule(id = "s1", medicationId = med1.id, bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING))
    val sched2 = MedicationSchedule(id = "s2", medicationId = med2.id, bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING))
    repo.saveMedication(med1, listOf(sched1))
    repo.saveMedication(med2, listOf(sched2))

    val prior = DoseLog(
      id = "log_prior",
      medicationId = med1.id,
      scheduleId = sched1.id,
      bucketId = TimeOfDayBucket.BUCKET_MORNING,
      scheduledDateIso = LocalDate.now().toString(),
      status = DoseStatus.SNOOZED,
      snoozedUntilMillis = System.currentTimeMillis() + 60_000L,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    repo.updateDoseLog(prior)

    val written = repo.logDoses(
      listOf(
        RegimenRepository.DoseLogRequest(
          medicationId = med1.id,
          scheduleId = sched1.id,
          bucketId = TimeOfDayBucket.BUCKET_MORNING,
          existingLogId = prior.id,
        ),
        RegimenRepository.DoseLogRequest(
          medicationId = med2.id,
          scheduleId = sched2.id,
          bucketId = TimeOfDayBucket.BUCKET_MORNING,
        ),
      ),
    )
    assertEquals(2, written.size)
    assertEquals(2, repo.snapshot.value.doseLogs.count { it.status == DoseStatus.TAKEN })

    // Undo restores prior snooze on med1 and soft-deletes the new log for med2.
    repo.restoreAndDeleteDoseLogs(
      toRestore = listOf(prior),
      toDeleteIds = listOf(written[1].id),
    )
    val liveLogs = repo.snapshot.value.doseLogs
    assertEquals(1, liveLogs.size)
    assertEquals(DoseStatus.SNOOZED, liveLogs.single().status)
    assertEquals(prior.id, liveLogs.single().id)
  }

  @Test
  fun saveScheduleEdit_atomicallyVersionsScheduleAndMigratesFutureAdjustment() {
    val today = LocalDate.parse("2026-11-15")
    val oldSched = MedicationSchedule(
      id = "sched_old",
      medicationId = "med_v",
      bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
      startDateIso = "2026-11-01",
    )
    val med = Medication(
      id = "med_v",
      formulaName = "Isotretinoin",
      strength = "20 mg",
      adjustments = listOf(
        MedicationAdjustment(
          id = "adj_future",
          effectiveDateIso = "2026-12-01",
          scheduleDoses = listOf(ScheduleDose(oldSched.id, 3.0, DoseUnit.CAPSULE)),
        ),
      ),
    )
    repo.saveMedication(med, listOf(oldSched))

    val edited = oldSched.copy(bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING, TimeOfDayBucket.BUCKET_EVENING))
    val result = ScheduleVersioning.apply(
      medication = med,
      original = oldSched,
      edited = edited,
      mode = Adjustments.EditMode.Change(today.toString()),
      today = today,
      buckets = repo.snapshot.value.buckets,
    )
    repo.saveScheduleEdit(result.updatedMedication, result.schedulesToSave)

    val savedSchedules = repo.snapshot.value.schedules.filter { it.medicationId == med.id }
    assertEquals(2, savedSchedules.size)
    val savedOld = savedSchedules.first { it.id == oldSched.id }
    val savedNew = savedSchedules.first { it.id != oldSched.id }
    assertEquals("2026-11-14", savedOld.endDateIso)
    assertEquals("2026-11-15", savedNew.startDateIso)

    val savedMed = repo.snapshot.value.medications.single { it.id == med.id }
    assertEquals(3.0, Adjustments.doseOn(savedMed, savedNew, LocalDate.parse("2026-12-01")).amount, 0.0)
    assertNotEquals(oldSched.id, savedMed.adjustments.single().scheduleDoses.single().scheduleId)
  }
}
