package com.exaclast.regimen.data

import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.Provider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalTrackingTest {

  private val doc = Provider(id = "p1", name = "Dr. Rostova")
  private val condition = Condition(id = "c1", name = "Hypertension")

  @Test
  fun unset_followsWhetherRecordsExist() {
    // New install: nothing entered, so both are hidden.
    val fresh = RegimenSnapshot()
    assertFalse(fresh.tracksProviders)
    assertFalse(fresh.tracksConditions)

    // Existing user with records keeps seeing them after updating.
    val existing = RegimenSnapshot(providers = listOf(doc), conditions = listOf(condition))
    assertTrue(existing.tracksProviders)
    assertTrue(existing.tracksConditions)

    // Deleted (tombstoned) records don't count.
    val onlyDeleted = RegimenSnapshot(providers = listOf(doc.copy(deletedAtEpochMillis = 1L)))
    assertFalse(onlyDeleted.tracksProviders)
  }

  @Test
  fun explicitChoice_winsOverData() {
    val hiddenDespiteData = RegimenSnapshot(
      settings = AppSettings(trackProviders = false, trackConditions = false),
      providers = listOf(doc),
      conditions = listOf(condition),
    )
    assertFalse(hiddenDespiteData.tracksProviders)
    assertFalse(hiddenDespiteData.tracksConditions)

    val shownWithoutData = RegimenSnapshot(settings = AppSettings(trackProviders = true, trackConditions = true))
    assertTrue(shownWithoutData.tracksProviders)
    assertTrue(shownWithoutData.tracksConditions)
  }
}
