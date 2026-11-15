package com.exaclast.regimen.sync

import com.exaclast.regimen.TestTexts
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.SyncDevice
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilesTest {

  private val med = Medication(id = "m1", formulaName = "Metformin", strength = "500 mg", updatedAtEpochMillis = 100)

  private fun log(id: String, date: String, updated: Long = 100, deleted: Long? = null) = DoseLog(
    id = id,
    medicationId = med.id,
    scheduleId = "s1",
    bucketId = TimeOfDayBucket.BUCKET_MORNING,
    scheduledDateIso = date,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
    updatedAtEpochMillis = updated,
    deletedAtEpochMillis = deleted,
  )

  @Test
  fun encryptedAndPlainFilesOpen() {
    val bundle = RegimenBackupBundle(medications = listOf(med))
    val plain = BackupFiles.open(BackupFiles.encode(bundle, pretty = true))
    assertTrue(plain is BackupFiles.Opened.Plain)
    assertEquals(bundle, (plain as BackupFiles.Opened.Plain).bundle)

    val key = BundleCrypto.newDataKey()
    val file = BundleCrypto.encrypt(BackupFiles.encode(bundle), key, null)
    val encrypted = BackupFiles.open(file)
    assertTrue(encrypted is BackupFiles.Opened.Encrypted)
    assertEquals(bundle, BackupFiles.decodeBundle(BundleCrypto.decrypt(file, key)))
  }

  @Test(expected = BundleCrypto.UnsupportedFormatException::class)
  fun backupFromNewerAppVersionIsRejected() {
    BackupFiles.open("""{"format":"regimen_backup","version":99,"medications":[]}""".toByteArray())
  }

  @Test(expected = BundleCrypto.NotABackupException::class)
  fun randomFileIsRejected() {
    BackupFiles.open("hello, not a backup".toByteArray())
  }

  @Test(expected = BundleCrypto.NotABackupException::class)
  fun emptyJsonIsRejectedAsNotABackup() {
    BackupFiles.open("{}".toByteArray())
  }

  @Test(expected = BundleCrypto.NotABackupException::class)
  fun jsonWithoutFormatMarkerIsRejected() {
    BackupFiles.open("""{"version":1,"medications":[]}""".toByteArray())
  }

  @Test
  fun invalidBucketHourIsRejected() {
    val badBucketJson = """{"format":"regimen_backup","version":1,"buckets":[{"id":"b1","name":"Bad","hour":25,"minute":0,"endHour":10,"endMinute":0}]}"""
    assertThrows(BundleCrypto.CorruptedException::class.java) {
      BackupFiles.open(badBucketJson.toByteArray())
    }
  }

  @Test
  fun danglingScheduleMedicationIsRejected() {
    val bundle = RegimenBackupBundle(
      medications = emptyList(),
      schedules = listOf(
        MedicationSchedule(
          id = "s1",
          medicationId = "m_missing",
          bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING),
        ),
      ),
    )
    val ex = assertThrows(BackupFiles.InvalidBackupException::class.java) {
      BackupFiles.validate(bundle)
    }
    assertTrue(ex.reason.contains("m_missing"))
  }

  @Test
  fun danglingScheduleBucketIsRejected() {
    val bundle = RegimenBackupBundle(
      medications = listOf(med),
      schedules = listOf(
        MedicationSchedule(
          id = "s1",
          medicationId = med.id,
          bucketIds = listOf("nonexistent_bucket"),
        ),
      ),
    )
    val ex = assertThrows(BackupFiles.InvalidBackupException::class.java) {
      BackupFiles.validate(bundle)
    }
    assertTrue(ex.reason.contains("nonexistent_bucket"))
  }

  @Test
  fun danglingDoseLogMedicationIsRejected() {
    val bundle = RegimenBackupBundle(
      medications = emptyList(),
      doseLogs = listOf(log("l1", "2026-11-01")),
    )
    val ex = assertThrows(BackupFiles.InvalidBackupException::class.java) {
      BackupFiles.validate(bundle)
    }
    assertTrue(ex.reason.contains(med.id))
  }

  @Test
  fun scheduleWithInvalidDateRangeIsRejected() {
    val bundle = RegimenBackupBundle(
      medications = listOf(med),
      schedules = listOf(
        MedicationSchedule(
          id = "s1",
          medicationId = med.id,
          startDateIso = "2026-11-20",
          endDateIso = "2026-11-10",
        ),
      ),
    )
    val ex = assertThrows(BackupFiles.InvalidBackupException::class.java) {
      BackupFiles.validate(bundle)
    }
    assertTrue(ex.reason.contains("start date"))
  }

  @Test
  fun indicationSecondaryToItselfIsRejected() {
    val bundle = RegimenBackupBundle(
      indications = listOf(
        Indication(id = "ind1", name = "Headache", primaryIds = listOf("ind1")),
      ),
    )
    val ex = assertThrows(BackupFiles.InvalidBackupException::class.java) {
      BackupFiles.validate(bundle)
    }
    assertTrue(ex.reason.contains("secondary to itself"))
  }

  @Test
  fun validBundleWithRelationsPassesValidation() {
    val provider = Provider(id = "p1", name = "Dr. Smith")
    val indication = Indication(id = "ind1", name = "Hypertension", providerIds = listOf("p1"))
    val validMed = med.copy(prescriberId = "p1", indicationIds = listOf("ind1"))
    val schedule = MedicationSchedule(id = "s1", medicationId = validMed.id, bucketIds = listOf(TimeOfDayBucket.BUCKET_MORNING))
    val doseLog = log("l1", "2026-11-01")

    val bundle = RegimenBackupBundle(
      providers = listOf(provider),
      indications = listOf(indication),
      medications = listOf(validMed),
      schedules = listOf(schedule),
      doseLogs = listOf(doseLog),
    )
    BackupFiles.validate(bundle)
  }

  @Test
  fun previewCountsNewChangedAndOnlyOnDevice() {
    val device = RegimenBackupBundle(
      medications = listOf(med),
      doseLogs = listOf(log("a", "2026-11-01"), log("b", "2026-11-02"), log("gone", "2026-11-03", deleted = 200)),
    )
    val file = RegimenBackupBundle(
      exportedAtEpochMillis = 42,
      sourceDeviceId = "p",
      devices = listOf(SyncDevice("p", "Pixel 8a")),
      medications = listOf(med.copy(notes = "With food", updatedAtEpochMillis = 300)),
      doseLogs = listOf(log("a", "2026-11-01"), log("c", "2026-10-30"), log("gone", "2026-11-03")),
    )
    val preview = BackupFiles.preview(device, file)
    assertEquals("Pixel 8a", preview.sourceDeviceName)
    val meds = preview.kinds.single { TestTexts.get(it.labelRes) == "Medications" }
    assertEquals(1, meds.changed)
    assertEquals(0, meds.new)
    val history = preview.kinds.single { TestTexts.get(it.labelRes) == "Dose history" }
    assertEquals(3, history.inFile)
    // "c" is new; "gone" is deleted here but live in the file, so it comes back as new.
    assertEquals(2, history.new)
    assertEquals(1, history.onlyOnDevice)
    assertEquals("2026-10-30", preview.firstDoseDateIso)
    assertEquals("2026-11-03", preview.lastDoseDateIso)
    assertFalse(preview.identicalToDevice)
    assertTrue(BackupFiles.preview(device, device).identicalToDevice)
  }

  @Test
  fun mergeIntoDeviceKeepsEverythingNewerEditWinsAndKeepsDeviceSettings() {
    val claim = PrimaryClaim("phone", 3, 0)
    val device = RegimenBackupBundle(
      settings = AppSettings(snoozeMinutes = 45),
      primary = claim,
      devices = listOf(SyncDevice("phone", "Pixel")),
      medications = listOf(med.copy(notes = "device, newer", updatedAtEpochMillis = 500)),
      doseLogs = listOf(log("a", "2026-11-01")),
    )
    val file = RegimenBackupBundle(
      settings = AppSettings(snoozeMinutes = 15),
      primary = PrimaryClaim("old-phone", 9, 0),
      medications = listOf(
        med.copy(notes = "file, older", updatedAtEpochMillis = 200),
        med.copy(id = "m2", formulaName = "Lisinopril"),
      ),
      doseLogs = listOf(log("b", "2026-11-02")),
    )
    val merged = BackupFiles.mergeIntoDevice(device, file, deviceId = "phone").bundle
    assertEquals("device, newer", merged.medications.single { it.id == "m1" }.notes)
    assertEquals(setOf("m1", "m2"), merged.medications.map { it.id }.toSet())
    assertEquals("2026-11-02", merged.doseLogs.single { it.id == "b" }.scheduledDateIso)
    assertEquals(setOf("a", "b"), merged.doseLogs.map { it.id }.toSet())
    assertEquals(45, merged.settings.snoozeMinutes)
    assertEquals(claim, merged.primary)
  }

  @Test
  fun asNeededScheduleWithEmptyBucketsPassesValidation() {
    val bundle = RegimenBackupBundle(
      medications = listOf(med),
      schedules = listOf(
        MedicationSchedule(
          id = "s_prn",
          medicationId = med.id,
          frequencyType = com.exaclast.regimen.model.ScheduleFrequencyType.AS_NEEDED,
          bucketIds = emptyList(),
        ),
      ),
    )
    BackupFiles.validate(bundle)
  }

  @Test
  fun previewCountsOnlyLiveDoseWindows() {
    val live = TimeOfDayBucket(id = "b1", name = "Morning", hour = 7)
    val deleted = TimeOfDayBucket(id = "b2", name = "Lunch", hour = 12, updatedAtEpochMillis = 200, deletedAtEpochMillis = 200)
    val device = RegimenBackupBundle(buckets = listOf(live))
    val file = RegimenBackupBundle(buckets = listOf(live, deleted))
    val preview = BackupFiles.preview(device, file)
    val windows = preview.kinds.single { TestTexts.get(it.labelRes) == "Dose windows" }
    assertEquals(1, windows.inFile)
    assertEquals(0, windows.new)
  }
}
