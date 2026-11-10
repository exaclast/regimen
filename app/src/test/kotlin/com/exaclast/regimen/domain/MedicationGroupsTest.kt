package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationGrouping
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MedicationGroupsTest {

  private val today = LocalDate.of(2026, 11, 9)
  private val infection = Indication(id = "i1", name = "Sinusitis", kind = IndicationKind.ACUTE)
  private val asthma = Indication(id = "i2", name = "Asthma", kind = IndicationKind.CHRONIC)

  private fun med(id: String, name: String = id, form: MedicationForm = MedicationForm.TABLET, indicationIds: List<String> = emptyList()) =
    Medication(id = id, formulaName = name, strength = "1 mg", form = form, indicationIds = indicationIds)

  private fun open(medicationId: String) = MedicationSchedule(medicationId = medicationId)

  private fun ending(medicationId: String, endIso: String) = MedicationSchedule(medicationId = medicationId, endDateIso = endIso)

  private fun prn(medicationId: String) =
    MedicationSchedule(medicationId = medicationId, frequencyType = ScheduleFrequencyType.AS_NEEDED)

  private fun course(
    medication: Medication,
    vararg schedules: MedicationSchedule,
    indications: List<Indication> = emptyList(),
  ) = MedicationGroups.courseOf(medication, schedules.toList(), indications, today)

  @Test
  fun openEndedScheduleIsOngoing() {
    assertEquals(MedicationCourse.ONGOING, course(med("m"), open("m")))
  }

  @Test
  fun scheduleWithAnEndDateIsShortTermThroughItsLastDay() {
    assertEquals(MedicationCourse.SHORT_TERM, course(med("m"), ending("m", "2026-11-09")))
    assertEquals(MedicationCourse.FINISHED, course(med("m"), ending("m", "2026-11-08")))
  }

  @Test
  fun oneOpenEndedScheduleKeepsAMedicationOngoing() {
    assertEquals(MedicationCourse.ONGOING, course(med("m"), ending("m", "2026-10-01"), open("m")))
  }

  @Test
  fun specificDatesRunThroughTheLatestDate() {
    val dates = MedicationSchedule(
      medicationId = "m",
      frequencyType = ScheduleFrequencyType.SPECIFIC_DATES,
      specificDatesIso = setOf("2026-11-01", "2026-11-20"),
    )
    assertEquals(MedicationCourse.SHORT_TERM, course(med("m"), dates))
  }

  @Test
  fun noScheduleOrOnlyAsNeededIsAsNeeded() {
    assertEquals(MedicationCourse.AS_NEEDED, course(med("m")))
    assertEquals(MedicationCourse.AS_NEEDED, course(med("m"), prn("m")))
  }

  @Test
  fun asNeededScheduleOutlivesAFinishedCourse() {
    assertEquals(MedicationCourse.AS_NEEDED, course(med("m"), ending("m", "2026-10-01"), prn("m")))
  }

  @Test
  fun deletedAndDisabledSchedulesDontCount() {
    val deleted = open("m").copy(deletedAtEpochMillis = 1L)
    val disabled = open("m").copy(enabled = false)
    assertEquals(MedicationCourse.FINISHED, course(med("m"), ending("m", "2026-10-01"), deleted, disabled))
  }

  @Test
  fun openEndedMedicationForOnlyAcuteIndicationsIsShortTerm() {
    assertEquals(
      MedicationCourse.SHORT_TERM,
      course(med("m", indicationIds = listOf("i1")), open("m"), indications = listOf(infection)),
    )
    // Also treating something chronic: it isn't going away with the infection.
    assertEquals(
      MedicationCourse.ONGOING,
      course(med("m", indicationIds = listOf("i1", "i2")), open("m"), indications = listOf(infection, asthma)),
    )
  }

  @Test
  fun finishedMedicationsStayOutOfSectionsUnderEveryGrouping() {
    val meds = listOf(med("done"), med("current"))
    val schedules = listOf(ending("done", "2026-10-01"), open("current"))
    MedicationGrouping.entries.forEach { grouping ->
      val grouped = MedicationGroups.group(grouping, meds, schedules, emptyList(), today)
      assertEquals(listOf("done"), grouped.finished.map { it.id })
      assertEquals(listOf("current"), grouped.sections.flatMap { it.medications }.map { it.id })
    }
  }

  @Test
  fun scheduleSectionsPutShortTermFirstAndSkipEmptyOnes() {
    val meds = listOf(med("a"), med("b"))
    val schedules = listOf(open("a"), ending("b", "2026-12-01"))
    val titles = MedicationGroups.group(MedicationGrouping.SCHEDULE, meds, schedules, emptyList(), today).sections.map { it.title }
    assertEquals(listOf("Short-term", "Ongoing"), titles)
  }

  @Test
  fun sectionsAreAlphabeticalByDisplayedName() {
    val meds = listOf(med("1", "zinc"), med("2", "Aspirin"), med("3", "ibuprofen").copy(brandName = "Advil"))
    val section = MedicationGroups.group(MedicationGrouping.FORM, meds, emptyList(), emptyList(), today).sections.single()
    assertEquals(listOf("Advil", "Aspirin", "zinc"), section.medications.map { it.primaryName })
  }

  @Test
  fun formSectionsFollowTheFormOrder() {
    val meds = listOf(med("a", form = MedicationForm.TOPICAL), med("b", form = MedicationForm.TABLET))
    val keys = MedicationGroups.group(MedicationGrouping.FORM, meds, emptyList(), emptyList(), today).sections.map { it.key }
    assertEquals(listOf("TABLET", "TOPICAL"), keys)
  }

  @Test
  fun aMedicationAppearsUnderEachIndicationItTreats() {
    val both = med("both", indicationIds = listOf("i1", "i2"))
    val none = med("none", indicationIds = listOf("deleted-indication"))
    val grouped = MedicationGroups.group(
      MedicationGrouping.INDICATION,
      listOf(both, none),
      listOf(open("both"), open("none")),
      listOf(infection, asthma),
      today,
    )
    // Acute before chronic, then anything unlinked.
    assertEquals(listOf("Sinusitis", "Asthma", "No indication"), grouped.sections.map { it.title })
    assertEquals(listOf("both"), grouped.sections[0].medications.map { it.id })
    assertEquals(listOf("both"), grouped.sections[1].medications.map { it.id })
    assertEquals(listOf("none"), grouped.sections[2].medications.map { it.id })
  }

  @Test
  fun filteringDropsSectionsLeftEmpty() {
    val meds = listOf(med("a"), med("b"), med("done"))
    val schedules = listOf(open("a"), ending("b", "2026-12-01"), ending("done", "2026-10-01"))
    val filtered = MedicationGroups.group(MedicationGrouping.SCHEDULE, meds, schedules, emptyList(), today)
      .filter { it.id == "a" }
    assertEquals(listOf("Ongoing"), filtered.sections.map { it.title })
    assertEquals(emptyList<Medication>(), filtered.finished)
  }

  @Test
  fun pastIndicationsStillInUseComeAfterCurrentOnes() {
    val resolved = asthma.copy(id = "i3", name = "Bronchitis", kind = IndicationKind.ACUTE, status = IndicationStatus.RESOLVED)
    val meds = listOf(med("a", indicationIds = listOf("i3")), med("b", indicationIds = listOf("i2")))
    val grouped = MedicationGroups.group(
      MedicationGrouping.INDICATION,
      meds,
      listOf(open("a"), open("b")),
      listOf(resolved, asthma),
      today,
    )
    assertEquals(listOf("Asthma", "Bronchitis (past)"), grouped.sections.map { it.title })
  }
}
