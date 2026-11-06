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
    assertEquals("00517-0031-25", FdaNdcLookupService.toCanonicalNdc11("0517-0031-25"))
    // 5-3-2 -> 5-4-2
    assertEquals("50580-0496-50", FdaNdcLookupService.toCanonicalNdc11("50580-496-50"))
    // 5-4-1 -> 5-4-2
    assertEquals("12345-6789-01", FdaNdcLookupService.toCanonicalNdc11("12345-6789-1"))
    // Unhyphenated 11 digits -> 5-4-2
    assertEquals("00517-0031-25", FdaNdcLookupService.toCanonicalNdc11("00517003125"))
  }

  @Test
  fun buildOpenFdaSearchQuery_includesUnpaddedOpenFdaCandidates() {
    val query = FdaNdcLookupService.buildOpenFdaSearchQuery("00517-0031-25")
    assertTrue(query.contains("packaging.package_ndc:\"0517-0031-25\""))
    assertTrue(query.contains("product_ndc:\"0517-0031\""))
  }

  @Test
  fun parseOpenFdaJson_extractsInjectableVialConcentrationAndRoute() {
    val sampleOpenFdaResponse = """
      {
        "results": [
          {
            "product_ndc": "0517-0031",
            "generic_name": "CYANOCOBALAMIN",
            "brand_name": "CYANOCOBALAMIN",
            "labeler_name": "American Regent, Inc.",
            "dosage_form": "INJECTION, SOLUTION",
            "route": ["INTRAMUSCULAR"],
            "active_ingredients": [
              {
                "name": "CYANOCOBALAMIN",
                "strength": "1 mg/mL"
              }
            ],
            "packaging": [
              {
                "package_ndc": "0517-0031-25",
                "description": "30 mL in 1 VIAL, MULTI-DOSE"
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val result = FdaNdcLookupService.parseOpenFdaJson(sampleOpenFdaResponse, listOf("00517-0031-25"))
    assertNotNull(result)
    assertEquals("Cyanocobalamin", result!!.formulaName)
    assertEquals("Cyanocobalamin", result.brandName)
    assertEquals("American Regent, Inc.", result.manufacturer)
    assertEquals("1 mg/mL", result.strength)
    assertEquals(MedicationForm.INJECTABLE_VIAL, result.form)
    assertEquals(DoseUnit.MG, result.defaultDoseUnit)
    assertEquals(InjectionRoute.IM, result.route)
    assertEquals(1.0, result.concentrationMgPerMl!!, 0.001)
    assertEquals(30.0, result.vialVolumeMl!!, 0.001)
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
    val gs1Paren = FdaNdcLookupService.parseDrugBarcode("(01)00305170031252(17)271231(10)LOT882")
    assertEquals("0517003125", gs1Paren.extractedNdc)
    assertEquals("2027-12-31", gs1Paren.expirationDateIso)
    assertEquals("LOT882", gs1Paren.lotNumber)

    // 2. Raw GS1 DataMatrix with AIM identifier "]d2"
    val gs1Raw = FdaNdcLookupService.parseDrugBarcode("]d201003505800496511728063010LOT42")
    assertEquals("5058004965", gs1Raw.extractedNdc)
    assertEquals("2028-06-30", gs1Raw.expirationDateIso)
    assertEquals("LOT42", gs1Raw.lotNumber)

    // 3. 12-digit UPC-A starting with '3' (3 + 10-digit NDC + check digit)
    val upcA = FdaNdcLookupService.parseDrugBarcode("305170031252")
    assertEquals("0517003125", upcA.extractedNdc)

    // 4. Candidate NDC-11s for a 10-digit unhyphenated barcode cover 4-4-2, 5-3-2, and 5-4-1
    val candidates = FdaNdcLookupService.candidateNdc11s("(01)00350580496502")
    assertTrue(candidates.contains("50580-0496-50"))

    // 5. Short OTC packaging code (e.g. "824208" on Allegra label corner) gives non-NDC hint
    val shortCode = FdaNdcLookupService.parseDrugBarcode("824208")
    assertNotNull(shortCode.nonNdcHint)
    assertTrue(shortCode.nonNdcHint!!.contains("Scan Label"))
  }

  @Test
  fun drugLabelParser_parsesGeminiNanoStructuredJsonOutputs() {
    // 1. Teva Amoxicillin-Clavulanate Rx Bottle JSON from Gemini Nano
    val amoxVial = DrugLabelParser.parseGeminiJsonResponse(
      """
      ```json
      {
        "ndc": null,
        "formulaName": "Amoxicillin and Clavulanate Potassium",
        "brandName": null,
        "strength": "875 mg / 125 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Teva",
        "prescriberName": null,
        "lotNumber": null,
        "expirationDateIso": null,
        "instructions": "Take 1 tablet by mouth twice daily with food",
        "isCompound": false
      }
      ```
      """.trimIndent()
    )
    assertEquals("Amoxicillin and Clavulanate Potassium", amoxVial.formulaName)
    assertEquals("875 mg / 125 mg", amoxVial.strength)
    assertEquals(MedicationForm.TABLET, amoxVial.form)
    assertEquals("Teva", amoxVial.manufacturer)
    assertEquals(1.0, amoxVial.defaultDoseAmount!!, 0.001)

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

    // 3. Multi-Ingredient Compound Bottle JSON
    val compound = DrugLabelParser.parseGeminiJsonResponse(
      """
      {
        "ndc": null,
        "formulaName": "Ascorbic Acid / Biotin / Vitamin B5 / Vitamin B6",
        "brandName": null,
        "strength": "100 mg / 2.5 mg / 11 mg / 2.5 mg",
        "form": "TABLET",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Town Compound Pharmacy",
        "prescriberName": "Robert Smith",
        "lotNumber": "LOT123456",
        "expirationDateIso": "2026-11-07",
        "instructions": "Chew or swallow 1 tablet by mouth daily",
        "isCompound": true
      }
      """.trimIndent()
    )
    assertTrue(compound.isCompound)
    assertTrue(compound.formulaName!!.contains("Ascorbic Acid"))
    assertTrue(compound.formulaName.contains("Biotin"))
    assertTrue(compound.strength!!.contains("100 mg"))
    assertEquals("Robert Smith", compound.prescriberName)
    assertEquals("LOT123456", compound.lotNumber)
    assertEquals("Town Compound Pharmacy", compound.manufacturer)
  }

  @Test
  fun crossReferenceDisambiguation_neverGuessesWithLimitedDataAndResolvesExactMfgMatch() {
    val pool = listOf(
      FdaDrugLookupResult(
        ndc11 = "00781-1852-20",
        formulaName = "Amoxicillin / Clavulanate Potassium",
        brandName = null,
        manufacturer = "Sandoz Inc.",
        strength = "875 mg / 125 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
      FdaDrugLookupResult(
        ndc11 = "00093-2274-34",
        formulaName = "Amoxicillin / Clavulanate Potassium",
        brandName = null,
        manufacturer = "Teva Pharmaceuticals USA, Inc.",
        strength = "875 mg / 125 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
      FdaDrugLookupResult(
        ndc11 = "00573-0150-20",
        formulaName = "Ibuprofen",
        brandName = "Advil",
        manufacturer = "GlaxoSmithKline Consumer Healthcare",
        strength = "200 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
      FdaDrugLookupResult(
        ndc11 = "00573-0160-20",
        formulaName = "Ibuprofen",
        brandName = "Advil",
        manufacturer = "GlaxoSmithKline Consumer Healthcare",
        strength = "400 mg",
        form = MedicationForm.TABLET,
        defaultDoseUnit = DoseUnit.TABLET,
      ),
    )

    // Case 1: Name + Strength + Form + Manufacturer ("SANDOZ") -> UnambiguousMatch for Sandoz
    val fullCriteria = DrugCrossReferenceCriteria(
      formulaName = "Amoxicillin / Clavulanate Potassium",
      strength = "875 mg / 125 mg",
      form = MedicationForm.TABLET,
      manufacturer = "SANDOZ",
    )
    val scoredFull = FdaNdcLookupService.scoreAndFilterCandidates(pool, fullCriteria)
    val outcomeFull = FdaNdcLookupService.evaluateDisambiguation(scoredFull, fullCriteria)
    assertTrue(outcomeFull is CrossReferenceOutcome.UnambiguousMatch)
    assertEquals(
      "00781-1852-20",
      (outcomeFull as CrossReferenceOutcome.UnambiguousMatch).candidate.result.ndc11
    )

    // Case 2: Name + Strength WITHOUT Manufacturer when multiple manufacturers exist -> NeedsUserSelection
    val noMfgCriteria = DrugCrossReferenceCriteria(
      formulaName = "Amoxicillin / Clavulanate Potassium",
      strength = "875 mg / 125 mg",
      form = MedicationForm.TABLET,
      manufacturer = null,
    )
    val scoredNoMfg = FdaNdcLookupService.scoreAndFilterCandidates(pool, noMfgCriteria)
    val outcomeNoMfg = FdaNdcLookupService.evaluateDisambiguation(scoredNoMfg, noMfgCriteria)
    assertTrue(outcomeNoMfg is CrossReferenceOutcome.NeedsUserSelection)

    // Case 3: Brand Name ("Advil") WITHOUT Strength when multiple strengths exist -> NeedsUserSelection
    val noStrengthCriteria = DrugCrossReferenceCriteria(
      brandName = "Advil",
      strength = null,
    )
    val scoredNoStrength = FdaNdcLookupService.scoreAndFilterCandidates(pool, noStrengthCriteria)
    val outcomeNoStrength = FdaNdcLookupService.evaluateDisambiguation(scoredNoStrength, noStrengthCriteria)
    assertTrue(outcomeNoStrength is CrossReferenceOutcome.NeedsUserSelection)

    // Case 4: Fuzzy / OCR-corrupted name ("Amox1cillin / Clavulanate") + Strength + Mfg -> UnambiguousMatch
    val fuzzyOcrCriteria = DrugCrossReferenceCriteria(
      formulaName = "Amox1cillin / Clavulanate",
      strength = "875 mg / 125 mg",
      form = MedicationForm.TABLET,
      manufacturer = "5AND0Z",
    )
    val scoredFuzzy = FdaNdcLookupService.scoreAndFilterCandidates(pool, fuzzyOcrCriteria)
    val outcomeFuzzy = FdaNdcLookupService.evaluateDisambiguation(scoredFuzzy, fuzzyOcrCriteria)
    assertTrue(outcomeFuzzy is CrossReferenceOutcome.UnambiguousMatch)
    assertEquals(
      "00781-1852-20",
      (outcomeFuzzy as CrossReferenceOutcome.UnambiguousMatch).candidate.result.ndc11
    )
  }

  /** A product with several package sizes; the FDA lists the -14 bottle first. */
  private val multiPackageResponse = """
    {
      "results": [
        {
          "product_ndc": "4580-2112",
          "generic_name": "UNRELATED DRUG",
          "labeler_name": "Other Labeler",
          "dosage_form": "TABLET",
          "active_ingredients": [{ "name": "UNRELATED DRUG", "strength": "5 mg/1" }],
          "packaging": [{ "package_ndc": "4580-2112-01", "description": "100 TABLET in 1 BOTTLE" }]
        },
        {
          "product_ndc": "45802-112",
          "generic_name": "EXAMPLEDRUG",
          "labeler_name": "Perrigo",
          "dosage_form": "TABLET",
          "active_ingredients": [{ "name": "EXAMPLEDRUG", "strength": "10 mg/1" }],
          "packaging": [
            { "package_ndc": "45802-112-14", "description": "14 TABLET in 1 BOTTLE" },
            { "package_ndc": "45802-112-22", "description": "30 TABLET in 1 BOTTLE" },
            { "package_ndc": "45802-112-78", "description": "90 TABLET in 1 BOTTLE" }
          ]
        }
      ]
    }
  """.trimIndent()

  @Test
  fun parseOpenFdaJson_picksEnteredPackageNotFirstListed() {
    // Bare 10-digit entry 4580211222 is ambiguous (4-4-2 / 5-3-2 / 5-4-1); only 5-3-2 is a real
    // package, and it is not the first package of its product nor the first search result.
    val result = FdaNdcLookupService.parseOpenFdaJson(
      multiPackageResponse,
      FdaNdcLookupService.candidateNdc11s("4580211222"),
    )
    assertNotNull(result)
    assertEquals("45802-0112-22", result!!.ndc11)
    assertEquals("Exampledrug", result.formulaName)
    assertEquals("30 TABLET in 1 BOTTLE", result.packageDescription)
  }

  @Test
  fun parseOpenFdaJson_keepsEnteredNdcWhenPackageNotListed() {
    // Product matches but this package size isn't in the FDA listing (e.g. discontinued):
    // keep the entered NDC rather than substituting another bottle size.
    val singleProduct = """
      {
        "results": [
          {
            "product_ndc": "45802-112",
            "generic_name": "EXAMPLEDRUG",
            "dosage_form": "TABLET",
            "active_ingredients": [{ "name": "EXAMPLEDRUG", "strength": "10 mg/1" }],
            "packaging": [
              { "package_ndc": "45802-112-14", "description": "14 TABLET in 1 BOTTLE" },
              { "package_ndc": "45802-112-22", "description": "30 TABLET in 1 BOTTLE" }
            ]
          }
        ]
      }
    """.trimIndent()
    val result = FdaNdcLookupService.parseOpenFdaJson(
      singleProduct,
      FdaNdcLookupService.candidateNdc11s("4580211250"),
    )
    assertNotNull(result)
    assertEquals("45802-0112-50", result!!.ndc11)
    assertEquals("Exampledrug", result.formulaName)
  }

  @Test
  fun buildOpenFdaSearchQuery_packageOnlyCoversAllThreeTenDigitLayouts() {
    val query = FdaNdcLookupService.buildOpenFdaSearchQuery("4580211222", includeProducts = false)
    // The FDA stores 10-digit hyphenated NDCs; each possible layout must be queried exactly.
    assertTrue(query.contains("packaging.package_ndc:\"4580-2112-22\"")) // 4-4-2
    assertTrue(query.contains("packaging.package_ndc:\"45802-112-22\"")) // 5-3-2
    assertTrue(query.contains("packaging.package_ndc:\"45802-1122-2\"")) // 5-4-1
    assertTrue(!query.contains("product_ndc"))
  }

  @Test
  fun hyphenatedInputs_resolveToTheSinglePackageTheyName() {
    // Hyphens fix the layout, so only that one reading is used (no 3-way guessing).
    for (input in listOf("45802-112-22", "45802-0112-22", "45802011222")) {
      assertEquals(input, listOf("45802-0112-22"), FdaNdcLookupService.candidateNdc11s(input))
      val query = FdaNdcLookupService.buildOpenFdaSearchQuery(input, includeProducts = false)
      assertTrue(input, query.contains("packaging.package_ndc:\"45802-112-22\"")) // FDA's stored form
      assertTrue(input, !query.contains("4580-2112")) // other layouts aren't guessed
      val result = FdaNdcLookupService.parseOpenFdaJson(multiPackageResponse, FdaNdcLookupService.candidateNdc11s(input))
      assertEquals(input, "45802-0112-22", result!!.ndc11)
    }
    // 4-4-2 and 5-4-1 hyphenated layouts.
    assertEquals(listOf("04580-2112-22"), FdaNdcLookupService.candidateNdc11s("4580-2112-22"))
    assertEquals(listOf("45802-1122-02"), FdaNdcLookupService.candidateNdc11s("45802-1122-2"))
    // Spaces instead of hyphens fall back to the three-layout search.
    assertEquals(3, FdaNdcLookupService.candidateNdc11s("45802 112 22").size)
  }

  @Test
  fun mapDosageForm_usesPackagingToTellPensFromVials() {
    val svc = FdaNdcLookupService
    // Lantus vial vs SoloStar pen share the dosage form; only packaging differs.
    assertEquals(
      MedicationForm.INJECTABLE_VIAL,
      svc.mapDosageForm("INJECTION, SOLUTION", "1 VIAL, GLASS in 1 CARTON (0088-2220-33)  / 10 mL in 1 VIAL, GLASS"),
    )
    assertEquals(
      MedicationForm.INJECTABLE_PEN,
      svc.mapDosageForm("INJECTION, SOLUTION", "5 SYRINGE in 1 CARTON (0088-2219-05)  / 3 mL in 1 SYRINGE"),
    )
    // EpiPen: auto-injector listed as a glass syringe inside a container.
    assertEquals(
      MedicationForm.INJECTABLE_PEN,
      svc.mapDosageForm("INJECTION", "2 CONTAINER in 1 CARTON / 1 SYRINGE, GLASS in 1 CONTAINER / .3 mL in 1 SYRINGE, GLASS"),
    )
    // Enbrel: plain "SOLUTION", identified as injectable by its route.
    assertEquals(
      MedicationForm.INJECTABLE_PEN,
      svc.mapDosageForm("SOLUTION", "4 CARTRIDGE in 1 CARTON / 1 mL in 1 CARTRIDGE", listOf("SUBCUTANEOUS")),
    )
    assertEquals(
      MedicationForm.INJECTABLE_VIAL,
      svc.mapDosageForm("SOLUTION", "4 VIAL, SINGLE-DOSE in 1 CARTON / .5 mL in 1 VIAL, SINGLE-DOSE", listOf("SUBCUTANEOUS")),
    )
    // Humira: listed as a KIT containing a syringe.
    assertEquals(
      MedicationForm.INJECTABLE_PEN,
      svc.mapDosageForm("KIT", "2 KIT in 1 CARTON / 1 KIT in 1 KIT *  1 SYRINGE in 1 TRAY / .8 mL in 1 SYRINGE *  1 mL in 1 PACKET"),
    )
    // No packaging: injection defaults to vial; an oral solution stays liquid.
    assertEquals(MedicationForm.INJECTABLE_VIAL, svc.mapDosageForm("INJECTION", null))
    assertEquals(MedicationForm.LIQUID, svc.mapDosageForm("SOLUTION", "118 mL in 1 BOTTLE", listOf("ORAL")))
  }

  @Test
  fun parseVialVolumeMl_readsVialAndDeviceVolumes() {
    val svc = FdaNdcLookupService
    assertEquals(10.0, svc.parseVialVolumeMl("1 VIAL, GLASS in 1 CARTON / 10 mL in 1 VIAL, GLASS")!!, 0.0001)
    assertEquals(3.0, svc.parseVialVolumeMl("1 SYRINGE, PLASTIC in 1 CARTON / 3 mL in 1 SYRINGE, PLASTIC")!!, 0.0001)
    assertEquals(0.3, svc.parseVialVolumeMl("1 SYRINGE, GLASS in 1 CONTAINER / .3 mL in 1 SYRINGE, GLASS")!!, 0.0001)
    assertEquals(1.0, svc.parseVialVolumeMl("4 CARTRIDGE in 1 CARTON / 1 mL in 1 CARTRIDGE")!!, 0.0001)
  }

  @Test
  fun labelFormText_prefersDeviceOverGenericInjection() {
    assertEquals(MedicationForm.INJECTABLE_PEN, DrugLabelParser.mapFormString("INJECTABLE_PEN"))
    assertEquals(MedicationForm.INJECTABLE_PEN, DrugLabelParser.mapFormString("Pen injector"))
    assertEquals(MedicationForm.INJECTABLE_PEN, DrugLabelParser.mapFormString("auto-injector"))
    assertEquals(MedicationForm.INJECTABLE_PEN, DrugLabelParser.mapFormString("prefilled syringe"))
    assertEquals(MedicationForm.INJECTABLE_VIAL, DrugLabelParser.mapFormString("injection vial"))
    assertEquals(MedicationForm.INJECTABLE_VIAL, DrugLabelParser.mapFormString("injectable"))
  }
}
