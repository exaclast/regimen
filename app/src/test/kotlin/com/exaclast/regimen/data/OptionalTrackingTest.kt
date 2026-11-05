package com.exaclast.regimen.data

import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.Prescriber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalTrackingTest {

  private val doc = Prescriber(id = "p1", name = "Dr. Rostova")
  private val condition = Condition(id = "c1", name = "Hypertension")

  @Test
  fun unset_followsWhetherRecordsExist() {
    // New install: nothing entered, so both are hidden.
    val fresh = RegimenSnapshot()
    assertFalse(fresh.tracksPrescribers)
    assertFalse(fresh.tracksConditions)

    // Existing user with records keeps seeing them after updating.
    val existing = RegimenSnapshot(prescribers = listOf(doc), conditions = listOf(condition))
    assertTrue(existing.tracksPrescribers)
    assertTrue(existing.tracksConditions)

    // Deleted (tombstoned) records don't count.
    val onlyDeleted = RegimenSnapshot(prescribers = listOf(doc.copy(deletedAtEpochMillis = 1L)))
    assertFalse(onlyDeleted.tracksPrescribers)
  }

  @Test
  fun explicitChoice_winsOverData() {
    val hiddenDespiteData = RegimenSnapshot(
      settings = AppSettings(trackPrescribers = false, trackConditions = false),
      prescribers = listOf(doc),
      conditions = listOf(condition),
    )
    assertFalse(hiddenDespiteData.tracksPrescribers)
    assertFalse(hiddenDespiteData.tracksConditions)

    val shownWithoutData = RegimenSnapshot(settings = AppSettings(trackPrescribers = true, trackConditions = true))
    assertTrue(shownWithoutData.tracksPrescribers)
    assertTrue(shownWithoutData.tracksConditions)
  }
}
