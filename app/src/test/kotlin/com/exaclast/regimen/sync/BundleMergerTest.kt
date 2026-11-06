package com.exaclast.regimen.sync

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.SharedSettings
import com.exaclast.regimen.model.SyncDevice
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleMergerTest {

  private val phone = "phone"
  private val tablet = "tablet"

  private val med = Medication(id = "m1", formulaName = "Metformin", strength = "500 mg", updatedAtEpochMillis = 100)

  private fun log(id: String, updated: Long, status: DoseStatus = DoseStatus.TAKEN, date: String = "2026-11-05") = DoseLog(
    id = id,
    medicationId = med.id,
    scheduleId = "s1",
    bucketId = TimeOfDayBucket.BUCKET_MORNING,
    scheduledDateIso = date,
    status = status,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
    createdAtEpochMillis = updated,
    updatedAtEpochMillis = updated,
  )

  private fun bundle(
    primary: String? = phone,
    medications: List<Medication> = listOf(med),
    logs: List<DoseLog> = emptyList(),
    buckets: List<TimeOfDayBucket> = emptyList(),
    shared: SharedSettings = SharedSettings(),
    devices: List<SyncDevice> = emptyList(),
  ) = RegimenBackupBundle(
    primary = primary?.let { PrimaryClaim(it, epoch = 1, claimedAtEpochMillis = 1) },
    medications = medications,
    doseLogs = logs,
    buckets = buckets,
    sharedSettings = shared,
    devices = devices,
  )

  @Test
  fun changeOnOneSideIsTakenFromAnyDevice() {
    val base = bundle()
    val edited = med.copy(notes = "With food", updatedAtEpochMillis = 200)
    // Tablet (not primary) edited; the phone (primary) didn't: the tablet's edit survives.
    val result = BundleMerger.merge(base, local = bundle(), remote = bundle(medications = listOf(edited)), localDeviceId = phone)
    assertEquals("With food", result.bundle.medications.single().notes)
    assertTrue(result.conflicts.isEmpty())
    assertTrue(result.localChanged)
    assertFalse(result.remoteChanged)
  }

  @Test
  fun newLogsFromBothDevicesAreKept() {
    val base = bundle()
    val phoneLog = log("a", 200, date = "2026-11-04")
    val tabletLog = log("b", 300, date = "2026-11-05")
    val result = BundleMerger.merge(
      base, local = bundle(logs = listOf(phoneLog)), remote = bundle(logs = listOf(tabletLog)), localDeviceId = phone,
    )
    assertEquals(setOf("a", "b"), result.bundle.doseLogs.map { it.id }.toSet())
    assertTrue(result.localChanged)
    assertTrue(result.remoteChanged)
  }

  @Test
  fun conflictingEditPrimaryKeepsItsOwnEvenIfOlder() {
    val base = bundle()
    val phoneEdit = med.copy(notes = "phone", updatedAtEpochMillis = 200)
    val tabletEdit = med.copy(notes = "tablet", updatedAtEpochMillis = 300)
    val onPhone = BundleMerger.merge(
      base, bundle(medications = listOf(phoneEdit)), bundle(medications = listOf(tabletEdit)), localDeviceId = phone,
    )
    assertEquals("phone", onPhone.bundle.medications.single().notes)
    assertTrue(onPhone.conflicts.single().keptLocal)

    // On the tablet, the remote copy (the primary's upload) wins over the tablet's newer edit.
    val onTablet = BundleMerger.merge(
      base, bundle(medications = listOf(tabletEdit)), bundle(medications = listOf(phoneEdit)), localDeviceId = tablet,
    )
    assertEquals("phone", onTablet.bundle.medications.single().notes)
    assertFalse(onTablet.conflicts.single().keptLocal)
  }

  @Test
  fun conflictWithNoPrimaryNewerEditWins() {
    val base = bundle(primary = null)
    val older = med.copy(notes = "older", updatedAtEpochMillis = 200)
    val newer = med.copy(notes = "newer", updatedAtEpochMillis = 300)
    val result = BundleMerger.merge(
      base, bundle(primary = null, medications = listOf(newer)), bundle(primary = null, medications = listOf(older)),
      localDeviceId = tablet,
    )
    assertEquals("newer", result.bundle.medications.single().notes)
  }

  @Test
  fun hardDeletedWindowStaysDeleted() {
    val window = TimeOfDayBucket(id = "lunch", name = "Lunch", hour = 12)
    val base = bundle(buckets = listOf(window))
    // Tablet deleted the window; phone didn't touch it.
    val result = BundleMerger.merge(base, bundle(buckets = listOf(window)), bundle(buckets = emptyList()), localDeviceId = phone)
    assertTrue(result.bundle.buckets.isEmpty())
  }

  @Test
  fun withoutBaseLocalOnlyRecordsAreKept() {
    val local = bundle(logs = listOf(log("a", 200)))
    val remote = bundle(medications = listOf(med, med.copy(id = "m2", formulaName = "Lisinopril")))
    val result = BundleMerger.merge(base = null, local = local, remote = remote, localDeviceId = tablet)
    assertEquals(setOf("m1", "m2"), result.bundle.medications.map { it.id }.toSet())
    assertEquals(listOf("a"), result.bundle.doseLogs.map { it.id })
  }

  @Test
  fun sameDoseLoggedOnTwoDevicesKeepsPrimarysAndTombstonesOther() {
    val base = bundle()
    val phoneLog = log("phone-log", 200)
    val tabletLog = log("tablet-log", 300, status = DoseStatus.SKIPPED)
    val onTablet = BundleMerger.merge(
      base, bundle(logs = listOf(tabletLog)), bundle(logs = listOf(phoneLog)), localDeviceId = tablet, nowMillis = 999,
    )
    val live = onTablet.bundle.doseLogs.filter { it.deletedAtEpochMillis == null }
    assertEquals("phone-log", live.single().id)
    val dropped = onTablet.bundle.doseLogs.single { it.id == "tablet-log" }
    assertEquals(999L, dropped.deletedAtEpochMillis)
    assertTrue(onTablet.conflicts.single().description.contains("Metformin"))

    val onPhone = BundleMerger.merge(
      base, bundle(logs = listOf(phoneLog)), bundle(logs = listOf(tabletLog)), localDeviceId = phone, nowMillis = 999,
    )
    assertEquals("phone-log", onPhone.bundle.doseLogs.single { it.deletedAtEpochMillis == null }.id)
  }

  @Test
  fun tombstonedDuplicateDoesNotConflictAgain() {
    val keep = log("keep", 200)
    val dropped = log("drop", 300).copy(deletedAtEpochMillis = 400, updatedAtEpochMillis = 400)
    val merged = bundle(logs = listOf(keep, dropped))
    val result = BundleMerger.merge(merged, merged, merged, localDeviceId = tablet)
    assertTrue(result.conflicts.isEmpty())
    assertFalse(result.localChanged)
    assertFalse(result.remoteChanged)
  }

  @Test
  fun newestPrimaryClaimWins() {
    val base = bundle(primary = phone)
    val tabletClaim = PrimaryClaim(tablet, epoch = 2, claimedAtEpochMillis = 5)
    val result = BundleMerger.merge(
      base, local = bundle(primary = phone), remote = bundle().copy(primary = tabletClaim), localDeviceId = phone,
    )
    assertEquals(tablet, result.bundle.primary?.deviceId)
    // Simultaneous claims with the same epoch resolve the same way on every device.
    val a = PrimaryClaim("a", 3, 0)
    val b = PrimaryClaim("b", 3, 0)
    assertTrue(b.supersedes(a))
    assertFalse(a.supersedes(b))
  }

  @Test
  fun sharedSettingsMergeAndDevicesUnion() {
    val base = bundle()
    val tabletShared = SharedSettings(trackConditions = false, updatedAtEpochMillis = 300)
    val result = BundleMerger.merge(
      base,
      bundle(devices = listOf(SyncDevice(phone, "Pixel", firstSeenEpochMillis = 1, lastSyncedEpochMillis = 50))),
      bundle(
        shared = tabletShared,
        devices = listOf(
          SyncDevice(phone, "Pixel", firstSeenEpochMillis = 1, lastSyncedEpochMillis = 40),
          SyncDevice(tablet, "Tab", firstSeenEpochMillis = 10, lastSyncedEpochMillis = 60),
        ),
      ),
      localDeviceId = phone,
    )
    assertEquals(false, result.bundle.sharedSettings.trackConditions)
    assertEquals(listOf(phone, tablet), result.bundle.devices.map { it.id })
    assertEquals(50L, result.bundle.devices.first().lastSyncedEpochMillis)
  }
}
