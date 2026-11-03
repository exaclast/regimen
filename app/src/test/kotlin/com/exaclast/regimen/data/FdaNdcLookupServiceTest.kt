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

    // 5. Short OTC packaging code (e.g. "824208" on Allegra label corner) gives non-NDC hint
    val shortCode = FdaNdcLookupService.parseDrugBarcode("824208")
    assertNotNull(shortCode.nonNdcHint)
    assertTrue(shortCode.nonNdcHint!!.contains("Scan Label"))
  }

  @Test
  fun drugLabelParser_parsesGeminiNanoStructuredJsonOutputs() {
    // 1. Zydus Anastrozole 1mg Pharmacy Vial JSON from Gemini Nano
    val zydusVial = DrugLabelParser.parseGeminiJsonResponse(
      """
      ```json
      {
        "ndc": null,
        "formulaName": "Anastrozole",
        "brandName": null,
        "strength": "1 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Zydus",
        "prescriberName": null,
        "lotNumber": null,
        "expirationDateIso": null,
        "instructions": "Take 1 tablet by mouth 1 time a week",
        "isCompound": false
      }
      ```
      """.trimIndent()
    )
    assertEquals("Anastrozole", zydusVial.formulaName)
    assertEquals("1 mg", zydusVial.strength)
    assertEquals(MedicationForm.TABLET, zydusVial.form)
    assertEquals("Zydus", zydusVial.manufacturer)
    assertEquals(1.0, zydusVial.defaultDoseAmount!!, 0.001)

    // 2. Allegra Allergy 24HR OTC Bottle JSON (including canonical NDC-11 normalization from 41167-4122-2)
    val allegra = DrugLabelParser.parseGeminiJsonResponse(
      """
      {
        "ndc": "41167-4122-2",
        "formulaName": "Fexofenadine HCl",
        "brandName": "Allegra",
        "strength": "180 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Chattem, Inc.",
        "prescriberName": null,
        "lotNumber": "43336",
        "expirationDateIso": "2027-10-28",
        "instructions": null,
        "isCompound": false
      }
      """.trimIndent()
    )
    assertEquals("41167-4122-02", allegra.extractedNdc)
    assertEquals("Allegra", allegra.brandName)
    assertEquals("Fexofenadine HCl", allegra.formulaName)
    assertEquals("180 mg", allegra.strength)
    assertEquals("43336", allegra.lotNumber)
    assertEquals("2027-10-28", allegra.expirationDateIso)

    // 3. XeCare Multi-Ingredient Compound Bottle JSON
    val compound = DrugLabelParser.parseGeminiJsonResponse(
      """
      {
        "ndc": null,
        "formulaName": "Finasteride / Minoxidil / Biotin / Vitamin B5 / Vitamin B6 / Vitamin C",
        "brandName": null,
        "strength": "1.2 mg / 3 mg / 2.5 mg / 11 mg / 2.5 mg / 100 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "XeCare LLC",
        "prescriberName": "Jeffrey Vacek",
        "lotNumber": "DPTSESKSLIEE",
        "expirationDateIso": "2026-11-07",
        "instructions": "Chew or swallow 1 tablet by mouth daily",
        "isCompound": true
      }
      """.trimIndent()
    )
    assertTrue(compound.isCompound)
    assertTrue(compound.formulaName!!.contains("Finasteride"))
    assertTrue(compound.formulaName.contains("Minoxidil"))
    assertTrue(compound.strength!!.contains("1.2 mg"))
    assertEquals("Jeffrey Vacek", compound.prescriberName)
    assertEquals("DPTSESKSLIEE", compound.lotNumber)
    assertEquals("XeCare LLC", compound.manufacturer)
  }

  @Test
  fun crossReferenceDisambiguation_neverGuessesWithLimitedDataAndResolvesExactMfgMatch() {
    val pool = listOf(
      FdaDrugLookupResult(
        ndc11 = "68382-0205-05",
        formulaName = "Anastrozole",
        brandName = null,
        manufacturer = "Zydus Pharmaceuticals (USA) Inc.",
        strength = "1 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
      FdaDrugLookupResult(
        ndc11 = "16729-0035-10",
        formulaName = "Anastrozole",
        brandName = null,
        manufacturer = "Accord Healthcare, Inc.",
        strength = "1 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
      FdaDrugLookupResult(
        ndc11 = "59417-0103-10",
        formulaName = "Lisdexamfetamine Dimesylate",
        brandName = "Vyvanse",
        manufacturer = "Takeda Pharmaceuticals America, Inc.",
        strength = "30 mg",
        form = MedicationForm.CAPSULE,
        defaultDoseUnit = DoseUnit.CAPSULE,
      ),
      FdaDrugLookupResult(
        ndc11 = "59417-0104-10",
        formulaName = "Lisdexamfetamine Dimesylate",
        brandName = "Vyvanse",
        manufacturer = "Takeda Pharmaceuticals America, Inc.",
        strength = "40 mg",
        form = MedicationForm.CAPSULE,
        defaultDoseUnit = DoseUnit.CAPSULE,
      ),
    )

    // Case 1: Name + Strength + Form + Manufacturer ("ZYDUS") -> UnambiguousMatch for Zydus
    val fullCriteria = DrugCrossReferenceCriteria(
      formulaName = "Anastrozole",
      strength = "1 mg",
      form = MedicationForm.TABLET,
      manufacturer = "ZYDUS",
    )
    val scoredFull = FdaNdcLookupService.scoreAndFilterCandidates(pool, fullCriteria)
    val outcomeFull = FdaNdcLookupService.evaluateDisambiguation(scoredFull, fullCriteria)
    assertTrue(outcomeFull is CrossReferenceOutcome.UnambiguousMatch)
    assertEquals(
      "68382-0205-05",
      (outcomeFull as CrossReferenceOutcome.UnambiguousMatch).candidate.result.ndc11
    )

    // Case 2: Name + Strength WITHOUT Manufacturer when multiple manufacturers exist -> NeedsUserSelection
    val noMfgCriteria = DrugCrossReferenceCriteria(
      formulaName = "Anastrozole",
      strength = "1 mg",
      form = MedicationForm.TABLET,
      manufacturer = null,
    )
    val scoredNoMfg = FdaNdcLookupService.scoreAndFilterCandidates(pool, noMfgCriteria)
    val outcomeNoMfg = FdaNdcLookupService.evaluateDisambiguation(scoredNoMfg, noMfgCriteria)
    assertTrue(outcomeNoMfg is CrossReferenceOutcome.NeedsUserSelection)

    // Case 3: Brand Name ("Vyvanse") WITHOUT Strength when multiple strengths exist -> NeedsUserSelection
    val noStrengthCriteria = DrugCrossReferenceCriteria(
      brandName = "Vyvanse",
      strength = null,
    )
    val scoredNoStrength = FdaNdcLookupService.scoreAndFilterCandidates(pool, noStrengthCriteria)
    val outcomeNoStrength = FdaNdcLookupService.evaluateDisambiguation(scoredNoStrength, noStrengthCriteria)
    assertTrue(outcomeNoStrength is CrossReferenceOutcome.NeedsUserSelection)

    // Case 4: Fuzzy / OCR-corrupted name ("Anastroz0le" or "Anastrazole") + Strength + Mfg -> UnambiguousMatch
    val fuzzyOcrCriteria = DrugCrossReferenceCriteria(
      formulaName = "Anastroz0le",
      strength = "1 mg",
      form = MedicationForm.TABLET,
      manufacturer = "ZYDU5",
    )
    val scoredFuzzy = FdaNdcLookupService.scoreAndFilterCandidates(pool, fuzzyOcrCriteria)
    val outcomeFuzzy = FdaNdcLookupService.evaluateDisambiguation(scoredFuzzy, fuzzyOcrCriteria)
    assertTrue(outcomeFuzzy is CrossReferenceOutcome.UnambiguousMatch)
    assertEquals(
      "68382-0205-05",
      (outcomeFuzzy as CrossReferenceOutcome.UnambiguousMatch).candidate.result.ndc11
    )
  }
}

