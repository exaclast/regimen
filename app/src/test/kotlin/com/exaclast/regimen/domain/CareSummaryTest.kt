package com.exaclast.regimen.domain

import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.AllergenType
import com.exaclast.regimen.model.Severity
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareSummaryTest {
  private val now = 1_000_000_000_000L
  private val day = 24L * 60 * 60 * 1000
  private val today = LocalDate.of(2026, 11, 13)
  private val metformin = Medication(formulaName = "Metformin", strength = "500 mg")
  private val lisinopril = Medication(formulaName = "Lisinopril", strength = "10 mg")

  private fun log(med: Medication, agoMillis: Long, amount: Double = 1.0, status: DoseStatus = DoseStatus.TAKEN) =
    DoseLog(
      medicationId = med.id,
      scheduledDateIso = "2026-11-13",
      actionTimestampMillis = now - agoMillis,
      status = status,
      amountTaken = amount,
      amountUnit = DoseUnit.TABLET,
    )

  private fun build(snapshot: RegimenSnapshot) =
    CareSummary.build(snapshot, now, today) { "t=$it" }

  private fun scheduled(med: Medication, endDateIso: String? = null) =
    MedicationSchedule(medicationId = med.id, endDateIso = endDateIso)

  @Test
  fun regimenDrugs_areSeparateFromFinishedOrAsNeeded_andShowTheLastDose() {
    val prn = Medication(formulaName = "Ibuprofen", strength = "200 mg")
    val finished = Medication(formulaName = "Amoxicillin", strength = "500 mg")
    val idle = Medication(formulaName = "Aspirin", strength = "81 mg")
    val summary = build(
      RegimenSnapshot(
        medications = listOf(metformin, lisinopril, prn, finished, idle),
        schedules = listOf(
          scheduled(metformin),
          scheduled(lisinopril),
          scheduled(idle),
          scheduled(finished, endDateIso = "2026-10-01"),
        ),
        doseLogs = listOf(
          log(metformin, 3 * day, amount = 1.0),
          log(metformin, day, amount = 2.0),
          log(lisinopril, day, status = DoseStatus.SKIPPED),
          log(prn, 80 * day),
          log(finished, 40 * day),
          log(finished, 100 * day),
        ),
      ),
    )
    val current = summary.sections.single { it.title == "Current medications" }.lines
    assertEquals(listOf("Aspirin 81 mg", "Lisinopril 10 mg", "Metformin 500 mg"), current.map { it.primary })
    assertEquals("1 tab, none taken in the past 90 days", current[1].secondary)
    assertEquals("2 tab, last taken t=${now - day}", current[2].secondary)

    val other = summary.sections.single { it.title.startsWith("Finished or as-needed") }.lines
    assertEquals(listOf("Amoxicillin 500 mg", "Ibuprofen 200 mg"), other.map { it.primary })
    assertTrue(other[0].secondary!!.startsWith("finished: "))
    assertTrue(other[1].secondary!!.startsWith("as needed: "))
  }

  @Test
  fun drugReactionsComeFirst_thenOtherAllergies() {
    val summary = build(
      RegimenSnapshot(
        medications = listOf(metformin),
        schedules = listOf(scheduled(metformin)),
        indications = listOf(
          Indication(name = "Peanut", kind = IndicationKind.ALLERGY, allergenType = AllergenType.FOOD),
          Indication(name = "Penicillin", kind = IndicationKind.ALLERGY, allergenType = AllergenType.DRUG),
        ),
      ),
    )
    assertEquals(
      listOf("Drug allergies and intolerances", "Other allergies", "Current medications"),
      summary.sections.map { it.title },
    )
  }

  @Test
  fun reactions_spellOutAllergyVersusIntolerance_andPastOnesAreLeftOut() {
    val summary = build(
      RegimenSnapshot(
        indications = listOf(
          Indication(name = "Penicillin", kind = IndicationKind.ALLERGY, allergenType = AllergenType.DRUG, severity = Severity.SEVERE, reaction = "Hives"),
          Indication(name = "Metformin", kind = IndicationKind.INTOLERANCE, allergenType = AllergenType.DRUG),
          Indication(name = "Sulfa", kind = IndicationKind.ALLERGY, allergenType = AllergenType.DRUG, status = IndicationStatus.RESOLVED),
        ),
      ),
    )
    val lines = summary.sections.first().lines
    assertEquals(listOf("Penicillin (allergy, drug)", "Metformin (intolerance, drug)"), lines.map { it.primary })
    assertEquals("Severe, Hives", lines[0].secondary)
    // The resolved one just moved to the recently ended list rather than staying current.
    assertEquals(listOf("Sulfa"), summary.sections.last().lines.map { it.primary })
  }

  @Test
  fun suspectedAndPastProviders_areSeparatedOrOmitted() {
    val summary = build(
      RegimenSnapshot(
        indications = listOf(
          Indication(name = "Diabetes", kind = IndicationKind.CHRONIC, severity = Severity.UNSURE),
          Indication(name = "Fatigue", kind = IndicationKind.CHRONIC, suspected = true),
        ),
        providers = listOf(
          Provider(name = "Dr. Active"),
          Provider(name = "Dr. Gone", careEndDateIso = "2025-01-01"),
        ),
      ),
    )
    assertEquals(listOf("Indications", "Suspected, not diagnosed", "Providers"), summary.sections.map { it.title })
    assertEquals(listOf("Dr. Active"), summary.sections.last().lines.map { it.primary })
    assertEquals("Chronic, Active, unsure severity", summary.sections.first().lines.single().secondary)
  }

  @Test
  fun endedIndications_appearOnlyIfTheyEndedWithinTheLookback() {
    val summary = CareSummary.build(
      RegimenSnapshot(
        indications = listOf(
          Indication(name = "Sinusitis", kind = IndicationKind.ACUTE, endDateIso = "2026-10-20"),
          Indication(name = "Old bronchitis", kind = IndicationKind.ACUTE, endDateIso = "2026-01-05"),
        ),
      ),
      now,
      today,
      formatTaken = { "t=$it" },
      formatDate = { "d=$it" },
    )
    val section = summary.sections.single()
    assertEquals("Indications ended in the past 90 days", section.title)
    assertEquals(listOf("Sinusitis"), section.lines.map { it.primary })
    assertEquals("Acute, ended d=2026-10-20", section.lines.single().secondary)
  }

  @Test
  fun text_namesThePatientAndListsEverySection() {
    val summary = build(
      RegimenSnapshot(
        settings = AppSettings(patientName = "Sam"),
        medications = listOf(metformin),
        schedules = listOf(scheduled(metformin)),
        doseLogs = listOf(log(metformin, day)),
      ),
    )
    val text = CareSummary.toText(summary, asOf = "Nov 13")
    assertTrue(text.startsWith("Health summary for Sam\nAs of Nov 13"))
    assertTrue(text.contains("Current medications\n- Metformin 500 mg — 1 tab, last taken"))
  }

  @Test
  fun emptyData_givesAnEmptySummary() {
    assertTrue(build(RegimenSnapshot()).isEmpty)
  }
}
