package com.exaclast.regimen.data

import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.SyringeType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Free-text syringe descriptions (database v4) become a syringe type. */
class SyringeMigrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun migrated(description: String): Medication = json.decodeFromString(
    RegimenDatabase.migrateSyringePayload(
      """{"formulaName":"X","strength":"1 mg/mL","injectableDetails":{"syringeVolumeMl":0.5,"syringeDescription":"$description"}}"""
    )
  )

  @Test
  fun recognisedTypeReplacesTheText() {
    val details = migrated("0.5 mL U-100 Insulin Syringe").injectableDetails!!
    assertEquals(SyringeType.INSULIN_U100, details.syringeType)
    assertNull(details.syringeDescription)
    assertEquals("0.5 mL U-100 insulin", details.formattedSyringe())
  }

  @Test
  fun luerLockAndUnknownTypes() {
    assertEquals(SyringeType.LUER_LOCK, migrated("3 mL Luer-Lok").injectableDetails!!.syringeType)
    val other = migrated("Glass syringe").injectableDetails!!
    assertEquals(SyringeType.OTHER, other.syringeType)
    assertEquals("Glass syringe", other.syringeDescription)
  }

  @Test
  fun alreadyMigratedOrNoDetailsIsUntouched() {
    val current = """{"formulaName":"X","injectableDetails":{"syringeType":"TUBERCULIN"}}"""
    assertEquals(current, RegimenDatabase.migrateSyringePayload(current))
    val tablet = """{"formulaName":"X"}"""
    assertEquals(tablet, RegimenDatabase.migrateSyringePayload(tablet))
  }
}
