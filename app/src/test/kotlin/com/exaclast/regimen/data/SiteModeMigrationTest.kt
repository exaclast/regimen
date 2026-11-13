package com.exaclast.regimen.data

import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.SiteMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Medications stored with the old rotation switch (database v3) keep their sites. */
class SiteModeMigrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun migrated(payload: String): Medication =
    json.decodeFromString<Medication>(RegimenDatabase.migrateSitePayload(payload))

  @Test
  fun rotationOnBecomesRotateWithItsSites() {
    val med = migrated(
      """{"formulaName":"Insulin","strength":"100 units/mL","siteRotationEnabled":true,"rotationSites":["A","B"]}"""
    )
    assertEquals(SiteMode.ROTATE, med.siteMode)
    assertEquals(listOf("A", "B"), med.sites)
  }

  @Test
  fun rotationOffBecomesOff() {
    val med = migrated(
      """{"formulaName":"Gel","strength":"1%","siteRotationEnabled":false,"rotationSites":["Shoulder"]}"""
    )
    assertEquals(SiteMode.OFF, med.siteMode)
    assertEquals(listOf("Shoulder"), med.sites)
  }

  @Test
  fun currentOrUnreadablePayloadIsUntouched() {
    val current = """{"formulaName":"Gel","siteMode":"RECORD","sites":["Shoulder"]}"""
    assertEquals(current, RegimenDatabase.migrateSitePayload(current))
    assertEquals("not json", RegimenDatabase.migrateSitePayload("not json"))
  }
}
