package com.exaclast.regimen.data

import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugLabelParserTest {

  @Test
  fun parseGeminiJsonResponse_extractsStandardMedicationWithDecoys() {
    val json = """
      {
        "patientName": "Chris Brown",
        "pharmacyName": "CVS Pharmacy #1234",
        "rxNumber": "Rx 654321",
        "ndc": "00071-0155-23",
        "formulaName": "Atorvastatin Calcium",
        "brandName": "Lipitor",
        "strength": "20 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Viatris",
        "prescriberName": "Dr. Sarah Smith",
        "lotNumber": "AB1234",
        "expirationDateIso": "2027-10-31",
        "instructions": "Take 1 tablet by mouth daily at bedtime",
        "isCompound": false
      }
    """.trimIndent()

    val parsed = DrugLabelParser.parseGeminiJsonResponse(
      rawOutput = json,
      userConfiguredPatientName = "Chris Brown",
    )

    assertEquals("00071-0155-23", parsed.extractedNdc)
    assertEquals("Atorvastatin Calcium", parsed.formulaName)
    assertEquals("Lipitor", parsed.brandName)
    assertEquals("20 mg", parsed.strength)
    assertEquals(MedicationForm.TABLET, parsed.form)
    assertEquals("Chris Brown", parsed.patientName)
    assertEquals("CVS Pharmacy #1234", parsed.pharmacyName)
    assertEquals("Rx 654321", parsed.rxNumber)
    assertEquals("Dr. Sarah Smith", parsed.prescriberName)
  }

  @Test
  fun parseGeminiJsonResponse_stripsPatientNameIfModelReturnsItAsBrand() {
    // Model erroneously put "Chris Brown" in brandName for an unbranded generic prescription
    val json = """
      {
        "patientName": "Chris Brown",
        "pharmacyName": "Walgreens #4567",
        "rxNumber": "Rx 987654",
        "ndc": "00093-7155-56",
        "formulaName": "Lisinopril",
        "brandName": "Chris Brown",
        "strength": "10 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Teva",
        "prescriberName": "Dr. Jane Doe",
        "instructions": "Take 1 tablet daily"
      }
    """.trimIndent()

    val parsed = DrugLabelParser.parseGeminiJsonResponse(
      rawOutput = json,
      userConfiguredPatientName = "Chris Brown",
    )

    assertEquals("Lisinopril", parsed.formulaName)
    // Brand name must be sanitized away because it matches the patient name!
    assertNull(parsed.brandName)
  }

  @Test
  fun parseGeminiJsonResponse_stripsUserConfiguredNameEvenIfPatientNameFieldWasOmitted() {
    val json = """
      {
        "ndc": "00093-7155-56",
        "formulaName": "Lisinopril",
        "brandName": "Christopher Brown",
        "strength": "10 mg",
        "form": "TABLET"
      }
    """.trimIndent()

    val parsed = DrugLabelParser.parseGeminiJsonResponse(
      rawOutput = json,
      userConfiguredPatientName = "Chris Brown",
    )

    assertEquals("Lisinopril", parsed.formulaName)
    assertNull("Fuzzy match with user-configured profile name must strip hallucinated brand", parsed.brandName)
  }

  @Test
  fun parseGeminiJsonResponse_stripsPrescriberOrPharmacyLeakedIntoBrand() {
    val json = """
      {
        "patientName": "John Customer",
        "pharmacyName": "Kroger Pharmacy",
        "prescriberName": "Dr. Gregory House",
        "formulaName": "Amoxicillin",
        "brandName": "Gregory House",
        "strength": "500 mg",
        "form": "CAPSULE"
      }
    """.trimIndent()

    val parsed = DrugLabelParser.parseGeminiJsonResponse(
      rawOutput = json,
      userConfiguredPatientName = "John Customer",
    )

    assertEquals("Amoxicillin", parsed.formulaName)
    assertNull(parsed.brandName)
  }

  private fun cleanBrand(brand: String, patient: String? = null): String? =
    DrugLabelParser.sanitizeNames(
      formula = "Generic",
      brand = brand,
      patientNameOnLabel = patient,
      userConfiguredPatientName = null,
      prescriber = null,
      pharmacy = null,
    ).second

  @Test
  fun sanitizeNames_keepsBrandsThatContainDr() {
    listOf("Androgel", "Androderm", "Hydrea", "Dramamine", "Drisdol").forEach { brand ->
      assertEquals(brand, cleanBrand(brand))
    }
  }

  @Test
  fun sanitizeNames_stillDropsLabelWordsUsedAsBrand() {
    listOf("Dr. Smith", "Dr Smith", "Tablet", "Tablets", "Patient Copy", "Take Daily", "Refill").forEach { brand ->
      assertNull(brand, cleanBrand(brand))
    }
  }

  @Test
  fun sanitizeNames_shortNamesOnlyMatchWholeWords() {
    assertEquals("Mannitol", cleanBrand("Mannitol", patient = "Ann Lee"))
    assertNull(cleanBrand("Ann Lee", patient = "Ann Lee"))
  }

  @Test
  fun timeOfDayBucket_crossesMidnightCalculation() {
    val morningBucket = TimeOfDayBucket(
      id = "m",
      name = "Morning",
      hour = 7,
      minute = 0,
      endHour = 10,
      endMinute = 0,
    )
    assertFalse(morningBucket.crossesMidnight)

    val overnightBucket = TimeOfDayBucket(
      id = "late",
      name = "Late Night",
      hour = 22,
      minute = 30,
      endHour = 1,
      endMinute = 30,
    )
    assertTrue(overnightBucket.crossesMidnight)
    assertEquals(22, overnightBucket.startHour)
    assertEquals(30, overnightBucket.startMinute)
    assertEquals("10:30 PM", overnightBucket.formattedStartTime())
    assertEquals("1:30 AM", overnightBucket.formattedEndTime())
  }
}

