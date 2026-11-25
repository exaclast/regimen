package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationGrouping
import androidx.annotation.StringRes
import com.exaclast.regimen.R
import com.exaclast.regimen.model.Labeled
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.RegimenItem
import com.exaclast.regimen.model.Texts
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate

/** How a medication is being taken. Declared in display order. */
enum class MedicationCourse(override val labelRes: Int) : Labeled {
  SHORT_TERM(R.string.enum_course_short_term),
  ONGOING(R.string.enum_course_ongoing),
  AS_NEEDED(R.string.enum_course_as_needed),
  FINISHED(R.string.enum_course_finished),
}

/**
 * A list section. The title is [titleRes] formatted with [title] as its one argument, or [title] alone
 * when there is no resource (an indication's own name).
 */
data class MedicationSection(
  val key: String,
  val medications: List<RegimenItem>,
  val title: String = "",
  @param:StringRes val titleRes: Int? = null,
) {
  fun label(texts: Texts): String = titleRes?.let { texts.get(it, title) } ?: title
}

data class GroupedMedications(val sections: List<MedicationSection>, val finished: List<RegimenItem>) {
  /** Narrows an already grouped list, so a search can't change which section anything is in. */
  fun filter(predicate: (RegimenItem) -> Boolean): GroupedMedications = GroupedMedications(
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
    medication: RegimenItem,
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
    medications: List<RegimenItem>,
    schedules: List<MedicationSchedule>,
    indications: List<Indication>,
    today: LocalDate,
  ): GroupedMedications {
    fun course(medication: RegimenItem) = courseOf(medication, schedules, indications, today)
    val (finished, current) = medications
      .sortedBy { it.primaryName.lowercase() }
      .partition { course(it) == MedicationCourse.FINISHED }

    val sections = when (grouping) {
      MedicationGrouping.SCHEDULE -> MedicationCourse.entries.mapNotNull { option ->
        section(option.name, current.filter { course(it) == option }, titleRes = option.labelRes)
      }
      MedicationGrouping.FORM -> MedicationForm.entries.mapNotNull { form ->
        section(form.name, current.filter { it.form == form }, titleRes = form.labelRes)
      }
      MedicationGrouping.INDICATION -> byIndication(current, medications, schedules, indications, today)
    }
    return GroupedMedications(sections, finished)
  }

  private fun byIndication(
    current: List<RegimenItem>,
    medications: List<RegimenItem>,
    schedules: List<MedicationSchedule>,
    indications: List<Indication>,
    today: LocalDate,
  ): List<MedicationSection> {
    val (past, active) = indications.partition { IndicationTimeline.isArchived(it, today, medications, schedules) }
    fun ordered(group: List<Indication>) = IndicationTimeline.sections(group).flatMap { it.second }
    fun sectionFor(indication: Indication, titleRes: Int? = null) =
      section(indication.id, current.filter { indication.id in it.indicationIds }, indication.name, titleRes)

    val known = indications.mapTo(HashSet()) { it.id }
    return ordered(active).mapNotNull { sectionFor(it) } +
      ordered(past).mapNotNull { sectionFor(it, R.string.med_section_past_indication) } +
      listOfNotNull(
        section("no-indication", current.filter { it.indicationIds.none(known::contains) }, titleRes = R.string.med_section_no_indication),
      )
  }

  private fun section(
    key: String,
    medications: List<RegimenItem>,
    title: String = "",
    titleRes: Int? = null,
  ): MedicationSection? =
    medications.takeIf { it.isNotEmpty() }?.let { MedicationSection(key, it, title, titleRes) }
}
