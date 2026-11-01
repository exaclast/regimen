package com.exaclast.regimen.data

import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FdaNdcLookupServiceTest {

  @Test
  fun canonicalNdc11_converts10DigitAnd11DigitInputs() {
    // 4-4-2 -> 5-4-2
    assertEquals("00009-0085-10", FdaNdcLookupService.toCanonicalNdc11("0009-0085-10"))
    // 5-3-2 -> 5-4-2
    assertEquals("59417-0104-10", FdaNdcLookupService.toCanonicalNdc11("59417-104-10"))
    // 5-4-1 -> 5-4-2
    assertEquals("12345-6789-01", FdaNdcLookupService.toCanonicalNdc11("12345-6789-1"))
    // Unhyphenated 11 digits -> 5-4-2
    assertEquals("00009-0085-10", FdaNdcLookupService.toCanonicalNdc11("00009008510"))
  }

  @Test
  fun buildOpenFdaSearchQuery_includesUnpaddedOpenFdaCandidates() {
    val query = FdaNdcLookupService.buildOpenFdaSearchQuery("00009-0085-10")
    assertTrue(query.contains("packaging.package_ndc:\"0009-0085-10\""))
    assertTrue(query.contains("product_ndc:\"0009-0085\""))
  }

  @Test
  fun parseOpenFdaJson_extractsInjectableVialConcentrationAndRoute() {
    val sampleOpenFdaResponse = """
      {
        "results": [
          {
            "product_ndc": "0009-0085",
            "generic_name": "TESTOSTERONE CYPIONATE",
            "brand_name": "DEPO-TESTOSTERONE",
            "labeler_name": "Pharmacia & Upjohn Company LLC",
            "dosage_form": "INJECTION, SOLUTION",
            "route": ["INTRAMUSCULAR"],
            "active_ingredients": [
              {
                "name": "TESTOSTERONE CYPIONATE",
                "strength": "200 mg/mL"
              }
            ],
            "packaging": [
              {
                "package_ndc": "0009-0085-10",
                "description": "10 mL in 1 VIAL, MULTI-DOSE"
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val result = FdaNdcLookupService.parseOpenFdaJson(sampleOpenFdaResponse, "00009-0085-10")
    assertNotNull(result)
    assertEquals("Testosterone Cypionate", result!!.formulaName)
    assertEquals("Depo-testosterone", result.brandName)
    assertEquals("Pharmacia & Upjohn Company LLC", result.manufacturer)
    assertEquals("200 mg/mL", result.strength)
    assertEquals(MedicationForm.INJECTABLE_VIAL, result.form)
    assertEquals(DoseUnit.MG, result.defaultDoseUnit)
    assertEquals(InjectionRoute.IM, result.route)
    assertEquals(200.0, result.concentrationMgPerMl!!, 0.001)
    assertEquals(10.0, result.vialVolumeMl!!, 0.001)
  }

  @Test
  fun timeOfDayBucket_formatsWindowRangeCorrectly() {
    val morning = TimeOfDayBucket(
      id = TimeOfDayBucket.BUCKET_MORNING,
      name = "Morning",
      hour = 7,
      minute = 0,
      endHour = 10,
      endMinute = 0,
    )
    assertEquals("7:00 AM – 10:00 AM", morning.formattedTime())
  }

  @Test
  fun parseDrugBarcode_extractsNdcExpAndLotFromGs1DataMatrixAndUpc() {
    // 1. Parenthesized GS1 DataMatrix
    val gs1Paren = FdaNdcLookupService.parseDrugBarcode("(01)00300090085106(17)271231(10)PFZ882")
    assertEquals("0009008510", gs1Paren.extractedNdc)
    assertEquals("2027-12-31", gs1Paren.expirationDateIso)
    assertEquals("PFZ882", gs1Paren.lotNumber)

    // 2. Raw GS1 DataMatrix with AIM identifier "]d2"
    val gs1Raw = FdaNdcLookupService.parseDrugBarcode("]d201003594170104151728063010LOT42")
    assertEquals("5941701041", gs1Raw.extractedNdc)
    assertEquals("2028-06-30", gs1Raw.expirationDateIso)
    assertEquals("LOT42", gs1Raw.lotNumber)

    // 3. 12-digit UPC-A starting with '3' (3 + 10-digit NDC + check digit)
    val upcA = FdaNdcLookupService.parseDrugBarcode("300090085106")
    assertEquals("0009008510", upcA.extractedNdc)

    // 4. Candidate NDC-11s for a 10-digit unhyphenated barcode cover 4-4-2, 5-3-2, and 5-4-1
    val candidates = FdaNdcLookupService.candidateNdc11s("(01)00359417104105")
    assertTrue(candidates.contains("59417-0104-10"))
  }
}

