package com.exaclast.regimen.model

import com.exaclast.regimen.model.Concentration.AmountUnit
import com.exaclast.regimen.ui.screens.volumeHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The amount per mL comes from the strength (T-112), so these cover what labels and FDA records write. */
class ConcentrationTest {

  private fun parsed(strength: String?) = Concentration.parse(strength)

  @Test
  fun perOneMl() {
    assertEquals(Concentration(200.0, AmountUnit.MG), parsed("200 mg/mL"))
    assertEquals(Concentration(200.0, AmountUnit.MG), parsed("200mg/1mL"))
    assertEquals(Concentration(50.0, AmountUnit.MCG), parsed("50 mcg/mL"))
  }

  @Test
  fun totalInSeveralMlIsDivided() {
    assertEquals(Concentration(20.0, AmountUnit.MG), parsed("200 mg/10 mL"))
    assertEquals(Concentration(80.0, AmountUnit.MG), parsed("400 mg per 5 mL"))
    assertEquals(Concentration(1.0, AmountUnit.MG), parsed("0.5 mg/0.5 mL"))
    assertEquals(Concentration(100.0, AmountUnit.MG), parsed("1 g/10 mL"))
  }

  @Test
  fun unitsIncludingFdaSpellings() {
    assertEquals(Concentration(100.0, AmountUnit.UNITS), parsed("100 units/mL (U-100)"))
    assertEquals(Concentration(100.0, AmountUnit.UNITS), parsed("100 [iU]/mL"))
    assertEquals(Concentration(1000.0, AmountUnit.UNITS), parsed("1,000 [USP'U]/mL"))
    assertEquals("100 units/mL", parsed("100 units/mL (U-100)")!!.formatted())
  }

  @Test
  fun notPerMlOrAmbiguousGivesNothing() {
    assertNull(parsed("10 mg"))
    assertNull(parsed("1%"))
    assertNull(parsed("875 mg / 125 mg"))
    // A combination suspension has no single amount to divide by.
    assertNull(parsed("400 mg / 57 mg per 5 mL"))
    assertNull(parsed(""))
    assertNull(parsed(null))
  }

  @Test
  fun volumesConvertOnlyMatchingUnits() {
    val mg = Concentration(200.0, AmountUnit.MG)
    assertEquals(0.5, mg.volumeMl(100.0, DoseUnit.MG)!!, 1e-9)
    assertEquals(0.5, mg.volumeMl(100_000.0, DoseUnit.MCG)!!, 1e-9)
    assertNull(mg.volumeMl(20.0, DoseUnit.UNITS))
    assertNull(mg.volumeMl(1.0, DoseUnit.TABLET))

    // U-200: 20 units is 0.1 mL, not the 0.2 mL a U-100 assumption would give.
    val u200 = Concentration(200.0, AmountUnit.UNITS)
    assertEquals(0.1, u200.volumeMl(20.0, DoseUnit.UNITS)!!, 1e-9)
    assertNull(u200.volumeMl(20.0, DoseUnit.MG))
  }

  @Test
  fun medicationVolumesOnlyForVialsAndOralLiquids() {
    val vial = Medication(formulaName = "B12", strength = "1 mg/mL", form = MedicationForm.INJECTABLE_VIAL)
    val pen = vial.copy(form = MedicationForm.INJECTABLE_PEN)
    val liquid = Medication(formulaName = "Amoxicillin", strength = "400 mg/5 mL", form = MedicationForm.LIQUID)
    val tablet = Medication(formulaName = "Amoxicillin", strength = "500 mg", form = MedicationForm.TABLET)
    assertEquals(0.5, vial.doseVolumeMl(0.5, DoseUnit.MG)!!, 1e-9)
    assertNull(pen.doseVolumeMl(0.5, DoseUnit.MG))
    assertEquals(5.0, liquid.doseVolumeMl(400.0, DoseUnit.MG)!!, 1e-9)
    assertNull(tablet.doseVolumeMl(500.0, DoseUnit.MG))
    assertEquals("400 mg (5 mL)", liquid.copy(defaultDoseAmount = 400.0, defaultDoseUnit = DoseUnit.MG).formattedDefaultDose())
  }

  @Test
  fun editorHintExplainsOrWarns() {
    assertEquals("200 mg/mL. 100 mg is 0.5 mL." to false, volumeHint("200 mg/mL", 100.0, DoseUnit.MG))
    val unreadable = volumeHint("200 mg", 100.0, DoseUnit.MG)!!
    assertTrue(unreadable.first.startsWith("To show volumes"))
    assertFalse(unreadable.second)
    assertTrue(volumeHint("100 units/mL", 20.0, DoseUnit.MG)!!.second)
    assertNull(volumeHint("", 1.0, DoseUnit.MG))
  }
}
