package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationGrouping
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate

/** How a medication is being taken. Declared in display order. */
enum class MedicationCourse(val displayName: String) {
  SHORT_TERM("Short-term"),
  ONGOING("Ongoing"),
  AS_NEEDED("As needed"),
  FINISHED("Finished"),
}

data class MedicationSection(val key: String, val title: String, val medications: List<Medication>)

data class GroupedMedications(val sections: List<MedicationSection>, val finished: List<Medication>) {
  /** Narrows an already grouped list, so a search can't change which section anything is in. */
  fun filter(predicate: (Medication) -> Boolean): GroupedMedications = GroupedMedications(
    sections = sections.mapNotNull { section ->
      section.copy(medications = section.medications.filter(predicate)).takeIf { it.medications.isNotEmpty() }
    },
    finished = finished.filter(predicate),
  )
}

/**
 * Sections of the medication list. The schedule decides whether a medication is short-term rather
 * than its indication's kind, because indications are optional and a chronic condition can still
 * need a short course.
 */
object MedicationGroups {

  fun courseOf(
    medication: Medication,
    schedules: List<MedicationSchedule>,
    indications: List<Indication>,
    today: LocalDate,
  ): MedicationCourse {
    val (asNeeded, timed) = schedules
      .filter { it.medicationId == medication.id && it.deletedAtEpochMillis == null && it.enabled }
      .partition { it.frequencyType == ScheduleFrequencyType.AS_NEEDED }
    if (timed.isEmpty()) return MedicationCourse.AS_NEEDED

    val lastDays = timed.map { it.lastDayOrNull() }
    if (lastDays.any { it == null }) {
      // An antibiotic entered without an end date is still only for the infection.
      val linked = indications.filter { it.id in medication.indicationIds }
      val onlyAcute = linked.isNotEmpty() && linked.all { it.kind == IndicationKind.ACUTE }
      return if (onlyAcute) MedicationCourse.SHORT_TERM else MedicationCourse.ONGOING
    }
    return when {
      !lastDays.filterNotNull().max().isBefore(today) -> MedicationCourse.SHORT_TERM
      asNeeded.isNotEmpty() -> MedicationCourse.AS_NEEDED
      else -> MedicationCourse.FINISHED
    }
  }

  /**
   * Current medications in sections, alphabetical within each, with finished ones kept apart under
   * every grouping. Grouped by indication, a medication appears once per indication it treats.
   */
  fun group(
    grouping: MedicationGrouping,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    indications: List<Indication>,
    today: LocalDate,
  ): GroupedMedications {
    fun course(medication: Medication) = courseOf(medication, schedules, indications, today)
    val (finished, current) = medications
      .sortedBy { it.primaryName.lowercase() }
      .partition { course(it) == MedicationCourse.FINISHED }

    val sections = when (grouping) {
      MedicationGrouping.SCHEDULE -> MedicationCourse.entries.mapNotNull { option ->
        section(option.name, option.displayName, current.filter { course(it) == option })
      }
      MedicationGrouping.FORM -> MedicationForm.entries.mapNotNull { form ->
        section(form.name, form.displayName, current.filter { it.form == form })
      }
      MedicationGrouping.INDICATION -> byIndication(current, medications, schedules, indications, today)
    }
    return GroupedMedications(sections, finished)
  }

  private fun byIndication(
    current: List<Medication>,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    indications: List<Indication>,
    today: LocalDate,
  ): List<MedicationSection> {
    val (past, active) = indications.partition { IndicationTimeline.isArchived(it, today, medications, schedules) }
    fun ordered(group: List<Indication>) = IndicationTimeline.sections(group).flatMap { it.second }
    fun sectionFor(indication: Indication, title: String) =
      section(indication.id, title, current.filter { indication.id in it.indicationIds })

    val known = indications.mapTo(HashSet()) { it.id }
    return ordered(active).mapNotNull { sectionFor(it, it.name) } +
      ordered(past).mapNotNull { sectionFor(it, "${it.name} (past)") } +
      listOfNotNull(section("no-indication", "No indication", current.filter { it.indicationIds.none(known::contains) }))
  }

  private fun section(key: String, title: String, medications: List<Medication>): MedicationSection? =
    medications.takeIf { it.isNotEmpty() }?.let { MedicationSection(key, title, it) }
}
