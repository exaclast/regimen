package com.exaclast.regimen.data

import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalTrackingTest {

  private val doc = Provider(id = "p1", name = "Dr. Rostova")
  private val indication = Indication(id = "c1", name = "Hypertension")

  @Test
  fun unset_followsWhetherRecordsExist() {
    // New install: nothing entered, so both are hidden.
    val fresh = RegimenSnapshot()
    assertFalse(fresh.tracksProviders)
    assertFalse(fresh.tracksIndications)

    // Existing user with records keeps seeing them after updating.
    val existing = RegimenSnapshot(providers = listOf(doc), indications = listOf(indication))
    assertTrue(existing.tracksProviders)
    assertTrue(existing.tracksIndications)

    // Deleted (tombstoned) records don't count.
    val onlyDeleted = RegimenSnapshot(providers = listOf(doc.copy(deletedAtEpochMillis = 1L)))
    assertFalse(onlyDeleted.tracksProviders)
  }

  @Test
  fun explicitChoice_winsOverData() {
    val hiddenDespiteData = RegimenSnapshot(
      settings = AppSettings(trackProviders = false, trackIndications = false),
      providers = listOf(doc),
      indications = listOf(indication),
    )
    assertFalse(hiddenDespiteData.tracksProviders)
    assertFalse(hiddenDespiteData.tracksIndications)

    val shownWithoutData = RegimenSnapshot(settings = AppSettings(trackProviders = true, trackIndications = true))
    assertTrue(shownWithoutData.tracksProviders)
    assertTrue(shownWithoutData.tracksIndications)
  }

  @Test
  fun medicationLookup_findsActiveAndDeleted_andAllMedicationsCombinesThem() {
    val active = Medication(id = "m1", formulaName = "Amoxicillin", strength = "500 mg")
    val deleted = Medication(id = "m2", formulaName = "Prednisone", strength = "20 mg", deletedAtEpochMillis = 12345L)
    val snapshot = RegimenSnapshot(
      medications = listOf(active),
      deletedMedications = listOf(deleted),
    )

    assertEquals(listOf(active, deleted), snapshot.allMedications)
    assertEquals(active, snapshot.medication("m1"))
    assertEquals(deleted, snapshot.medication("m2"))
    assertNull(snapshot.medication("missing"))
  }
}
