package com.exaclast.regimen.data

import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Medication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Data stored before the rename from conditions to indications must keep working. */
class IndicationMigrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun migratedIds(payload: String): List<String> =
    json.decodeFromString<Medication>(RegimenDatabase.migrateMedicationPayload(payload)).indicationIds

  @Test
  fun singleLinkBecomesAList() {
    val payload = """{"formulaName":"Amoxicillin","strength":"875 mg","indicationConditionId":"c1"}"""
    assertEquals(listOf("c1"), migratedIds(payload))
  }

  @Test
  fun interimListKeyIsKept() {
    val payload = """{"formulaName":"Amoxicillin","strength":"875 mg","indicationConditionIds":["c1","c2"]}"""
    assertEquals(listOf("c1", "c2"), migratedIds(payload))
  }

  @Test
  fun noLinkStaysEmpty() {
    val payload = """{"formulaName":"Amoxicillin","strength":"875 mg","indicationConditionId":null}"""
    assertEquals(emptyList<String>(), migratedIds(payload))
  }

  @Test
  fun otherFieldsSurvive() {
    val payload = """{"id":"m1","formulaName":"Amoxicillin","strength":"875 mg","indicationConditionId":"c1"}"""
    val medication = json.decodeFromString<Medication>(RegimenDatabase.migrateMedicationPayload(payload))
    assertEquals("m1", medication.id)
    assertEquals("Amoxicillin", medication.formulaName)
    assertEquals("875 mg", medication.strength)
  }

  @Test
  fun alreadyMigratedOrUnreadablePayloadIsUntouched() {
    val current = """{"formulaName":"Amoxicillin","indicationIds":["c1"]}"""
    assertEquals(current, RegimenDatabase.migrateMedicationPayload(current))
    assertEquals("not json", RegimenDatabase.migrateMedicationPayload("not json"))
  }

  @Test
  fun storedTrackingSettingKeepsItsValue() {
    val settings = json.decodeFromString<AppSettings>("""{"trackConditions":false}""")
    assertFalse(settings.trackIndications!!)
  }
}
