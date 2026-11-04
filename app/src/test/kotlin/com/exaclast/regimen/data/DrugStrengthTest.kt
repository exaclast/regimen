package com.exaclast.regimen.data

import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugStrengthTest {

  @Test
  fun similarLookingStrengths_areNotEquivalent() {
    // Substring matching used to treat all of these as a match for "5 mg".
    listOf("0.5 mg", "2.5 mg", "25 mg", "15 mg", "7.5 mg").forEach { other ->
      assertFalse("5 mg vs $other", DrugStrength.equivalent("5 mg", other))
      assertFalse("$other vs 5 mg", DrugStrength.equivalent(other, "5 mg"))
    }
    assertTrue(DrugStrength.equivalent("5 mg", "5mg"))
    assertTrue(DrugStrength.equivalent("5 MG", "5 mg/1"))
  }

  @Test
  fun unitConversionsAndConcentrations_areNormalized() {
    assertTrue(DrugStrength.equivalent("1000 mcg", "1 mg"))
    assertTrue(DrugStrength.equivalent("1 g", "1000 mg"))
    assertTrue(DrugStrength.equivalent("200 mg/mL", "200 mg/1mL"))
    assertTrue(DrugStrength.equivalent("200 mg/mL", "2000 mg/10 mL"))
    assertTrue(DrugStrength.equivalent("2 mg/1.5 mL", "1.34 mg/mL")) // label rounding within 1%
    assertTrue(DrugStrength.equivalent("100 [iU]/mL", "100 units/mL"))
    assertTrue(DrugStrength.equivalent("0.05 mg/24 hr", "0.05 mg/d"))
    assertTrue(DrugStrength.equivalent("0,5 mg", "0.5 mg"))
    assertTrue(DrugStrength.equivalent("1,000 mg", "1 g"))

    assertFalse(DrugStrength.equivalent("200 mg/mL", "200 mg"))
    assertFalse(DrugStrength.equivalent("100 mg/mL", "200 mg/mL"))
    assertFalse(DrugStrength.equivalent("1 mg/mL", "1 mg/g"))
  }

  @Test
  fun multiIngredientStrengths_compareAsSets() {
    assertTrue(DrugStrength.equivalent("10 mg / 325 mg", "325 mg / 10 mg"))
    assertTrue(DrugStrength.equivalent("10-325 mg", "10 mg / 325 mg"))
    assertTrue(DrugStrength.equivalent("5 mg/325 mg", "5 mg / 325 mg"))
    assertFalse(DrugStrength.equivalent("10 mg / 325 mg", "7.5 mg / 325 mg"))
    assertFalse(DrugStrength.equivalent("10 mg / 325 mg", "10 mg"))
  }

  @Test
  fun unparseableStrengths_fallBackToExactTextOnly() {
    assertNull(DrugStrength.parse("See package"))
    assertTrue(DrugStrength.equivalent("See package", "see  PACKAGE"))
    assertFalse(DrugStrength.equivalent("See package", "See package insert"))
    assertFalse(DrugStrength.equivalent(null, "5 mg"))
  }

  @Test
  fun crossReference_neverAutoAppliesAWrongStrength() {
    fun warfarin(strength: String) = FdaDrugLookupResult(
      ndc11 = "00000-0000-00",
      formulaName = "Warfarin Sodium",
      brandName = null,
      manufacturer = "Taro",
      strength = strength,
      form = MedicationForm.TABLET,
      defaultDoseUnit = DoseUnit.TABLET,
    )
    val criteria = DrugCrossReferenceCriteria(formulaName = "Warfarin", strength = "5 mg", manufacturer = "Taro")

    // Only a wrong strength came back from OpenFDA: it must be filtered out, not auto-applied.
    val onlyWrong = FdaNdcLookupService.scoreAndFilterCandidates(listOf(warfarin("2.5 mg")), criteria)
    assertTrue(onlyWrong.isEmpty())

    val mixed = FdaNdcLookupService.scoreAndFilterCandidates(
      listOf(warfarin("0.5 mg"), warfarin("2.5 mg"), warfarin("5 mg"), warfarin("25 mg")),
      criteria,
    )
    assertEquals(listOf("5 mg"), mixed.map { it.result.strength })
    val outcome = FdaNdcLookupService.evaluateDisambiguation(mixed, criteria)
    assertTrue(outcome is CrossReferenceOutcome.UnambiguousMatch)
    assertEquals("5 mg", (outcome as CrossReferenceOutcome.UnambiguousMatch).candidate.result.strength)
  }

  @Test
  fun normalizeStrengthText_onlyDropsBarePerOneDenominator() {
    assertEquals("40 mg", FdaNdcLookupService.normalizeStrengthText("40 mg/1"))
    assertEquals("5 mg / 10 mg", FdaNdcLookupService.normalizeStrengthText("5 mg/1 / 10 mg/1"))
    assertEquals("200 mg/mL", FdaNdcLookupService.normalizeStrengthText("200 mg/1mL"))
    assertEquals("2 mg/1.5mL", FdaNdcLookupService.normalizeStrengthText("2 mg/1.5mL"))
    assertEquals("100 mg/10mL", FdaNdcLookupService.normalizeStrengthText("100 mg/10mL"))
    assertEquals("1000 [USP'U]/10mL", FdaNdcLookupService.normalizeStrengthText("1000 [USP'U]/10mL"))
  }
}
