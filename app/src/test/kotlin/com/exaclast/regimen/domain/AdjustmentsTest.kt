package com.exaclast.regimen.domain

import com.exaclast.regimen.domain.Adjustments.EditMode
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.SyringeType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dated adjustments (T-113): an isotretinoin titration and an injectable's equipment change. */
class AdjustmentsTest {

  private val today = LocalDate.parse("2026-11-13")
  private val schedule = MedicationSchedule(id = "s1", medicationId = "iso", doseAmount = 1.0, doseUnit = DoseUnit.CAPSULE)

  // 1 capsule to start, 2 from Nov 1, 3 planned from Dec 1.
  private val isotretinoin = Medication(
    id = "iso",
    formulaName = "Isotretinoin",
    strength = "20 mg",
    form = MedicationForm.CAPSULE,
    defaultDoseUnit = DoseUnit.CAPSULE,
    adjustments = listOf(
      MedicationAdjustment(id = "a2", effectiveDateIso = "2026-12-01", scheduleDoses = listOf(ScheduleDose("s1", 3.0, DoseUnit.CAPSULE))),
      MedicationAdjustment(id = "a1", effectiveDateIso = "2026-11-01", note = "2-week check", scheduleDoses = listOf(ScheduleDose("s1", 2.0, DoseUnit.CAPSULE))),
    ),
  )

  private fun dose(date: String) = Adjustments.doseOn(isotretinoin, schedule, LocalDate.parse(date)).amount

  @Test
  fun doseFollowsStepsByDate() {
    assertEquals(1.0, dose("2026-10-31"), 0.0)
    assertEquals(2.0, dose("2026-11-01"), 0.0)
    assertEquals(2.0, dose("2026-11-30"), 0.0)
    assertEquals(3.0, dose("2026-12-01"), 0.0)
    assertEquals(2.0, Adjustments.scheduleOn(isotretinoin, schedule, today).doseAmount, 0.0)
  }

  @Test
  fun firstDoseAtANewAmountIsFlagged() {
    assertTrue(Adjustments.doseChangesOn(isotretinoin, schedule, LocalDate.parse("2026-12-01")))
    assertFalse(Adjustments.doseChangesOn(isotretinoin, schedule, LocalDate.parse("2026-12-02")))
    assertEquals("a2", Adjustments.nextAfter(isotretinoin, today)?.id)
  }

  @Test
  fun scheduleEditAsChangeKeepsHistory() {
    val edited = schedule.copy(doseAmount = 4.0)
    val (med, saved) = Adjustments.applyScheduleEdit(isotretinoin, schedule, edited, EditMode.Change("2026-12-15", "Max dose"), today)
    assertEquals(1.0, saved.doseAmount, 0.0) // starting value untouched
    assertEquals(2.0, Adjustments.doseOn(med!!, saved, today).amount, 0.0)
    assertEquals(4.0, Adjustments.doseOn(med, saved, LocalDate.parse("2026-12-15")).amount, 0.0)
  }

  @Test
  fun scheduleFixCorrectsTheStepInEffect() {
    val edited = schedule.copy(doseAmount = 2.5)
    val (med, saved) = Adjustments.applyScheduleEdit(isotretinoin, schedule, edited, EditMode.Fix, today)
    // Today's value came from the Nov 1 step, so that's what's corrected; the start and Dec 1 stay.
    assertEquals(1.0, Adjustments.doseOn(med!!, saved, LocalDate.parse("2026-10-01")).amount, 0.0)
    assertEquals(2.5, Adjustments.doseOn(med, saved, today).amount, 0.0)
    assertEquals(3.0, Adjustments.doseOn(med, saved, LocalDate.parse("2026-12-01")).amount, 0.0)
  }

  @Test
  fun scheduleFixWithNoStepCorrectsTheStart() {
    val plain = isotretinoin.copy(adjustments = emptyList())
    val (med, saved) = Adjustments.applyScheduleEdit(plain, schedule, schedule.copy(doseAmount = 2.0), EditMode.Fix, today)
    assertNull(med)
    assertEquals(2.0, saved.doseAmount, 0.0)
  }

  private val vial = Medication(
    id = "t",
    formulaName = "Testosterone Cypionate",
    strength = "200 mg/mL",
    form = MedicationForm.INJECTABLE_VIAL,
    defaultDoseAmount = 100.0,
    defaultDoseUnit = DoseUnit.MG,
    injectableDetails = InjectableDetails(route = InjectionRoute.IM, injectNeedleGauge = 23),
  )

  @Test
  fun equipmentChangeFromADate() {
    val edited = vial.copy(
      injectableDetails = InjectableDetails(route = InjectionRoute.SC, syringeType = SyringeType.INSULIN_U100, syringeVolumeMl = 1.0, injectNeedleGauge = 29),
      notes = "Switched to SC",
    )
    val saved = Adjustments.applyMedicationEdit(vial, edited, EditMode.Change("2026-11-20"), today)
    assertEquals("Switched to SC", saved.notes) // untracked fields apply directly
    assertEquals(InjectionRoute.IM, Adjustments.asOf(saved, today).injectableDetails!!.route)
    assertEquals(InjectionRoute.SC, Adjustments.asOf(saved, LocalDate.parse("2026-11-20")).injectableDetails!!.route)
    assertEquals(setOf(Adjustments.Part.EQUIPMENT), saved.adjustments.single().let { a ->
      setOfNotNull(Adjustments.Part.EQUIPMENT.takeIf { a.injectableDetails != null }, Adjustments.Part.STRENGTH.takeIf { a.strength != null })
    })
  }

  @Test
  fun sameDayChangesMergeIntoOneAdjustment() {
    val withDose = Adjustments.upsert(vial, MedicationAdjustment(effectiveDateIso = "2026-12-01", defaultDose = DoseAmount(120.0, DoseUnit.MG)))
    val both = Adjustments.upsert(withDose, MedicationAdjustment(effectiveDateIso = "2026-12-01", strength = "250 mg/mL", note = "New vial"))
    val single = both.adjustments.single()
    assertEquals(DoseAmount(120.0, DoseUnit.MG), single.defaultDose)
    assertEquals("250 mg/mL", single.strength)
    assertEquals("New vial", single.note)
  }

  @Test
  fun askOnlyWithHistoryAndARealChange() {
    val log = DoseLog(medicationId = "t", scheduledDateIso = "2026-11-10", status = DoseStatus.TAKEN, amountTaken = 100.0, amountUnit = DoseUnit.MG)
    val edited = vial.copy(strength = "250 mg/mL")
    assertTrue(Adjustments.shouldAskForMedication(vial, edited, listOf(log), today))
    assertFalse(Adjustments.shouldAskForMedication(vial, edited, emptyList(), today)) // nothing to protect yet
    assertFalse(Adjustments.shouldAskForMedication(vial, vial.copy(notes = "x"), listOf(log), today))
    assertFalse(Adjustments.shouldAskForMedication(vial, edited.copy(form = MedicationForm.INJECTABLE_PEN), listOf(log), today))
    // A medication with no details and the editor's blank defaults aren't a change.
    val bare = vial.copy(injectableDetails = null)
    assertFalse(Adjustments.shouldAskForMedication(bare, bare.copy(injectableDetails = InjectableDetails()), listOf(log), today))
  }

  @Test
  fun describeListsWhatChanges() {
    val lines = Adjustments.describe(isotretinoin.adjustments.first { it.id == "a1" }) { if (it == "s1") "Evening" else null }
    assertEquals(listOf("Evening: 2 cap"), lines)
  }
}
