package com.exaclast.regimen.sync

import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.SyncDevice
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    BackupFiles.open("""{"version":99,"medications":[]}""".toByteArray())
  }

  @Test(expected = BundleCrypto.NotABackupException::class)
  fun randomFileIsRejected() {
    BackupFiles.open("hello, not a backup".toByteArray())
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
    val meds = preview.kinds.single { it.label == "Medications" }
    assertEquals(1, meds.changed)
    assertEquals(0, meds.new)
    val history = preview.kinds.single { it.label == "Dose history" }
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
    assertEquals(setOf("a", "b"), merged.doseLogs.map { it.id }.toSet())
    assertEquals(45, merged.settings.snoozeMinutes)
    assertEquals(claim, merged.primary)
  }
}
