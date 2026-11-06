package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionKind
import com.exaclast.regimen.model.ConditionStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate

/**
 * When conditions (and providers) end, and whether they're archived ("past").
 *
 * An acute condition with no end date ends with its medication course: the last scheduled day of
 * the medications linked to it, so an infection archives itself when the antibiotics finish. If any
 * linked course is open-ended (or there are none), there's no expected end.
 */
object ConditionTimeline {

  /** How the end date was determined, for display ("Ends Nov 15 with Amoxicillin"). */
  data class End(val date: LocalDate, val fromCourse: Boolean)

  fun end(
    condition: Condition,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): End? {
    parse(condition.endDateIso)?.let { return End(it, fromCourse = false) }
    if (condition.kind != ConditionKind.ACUTE) return null
    return courseEnd(condition, medications, schedules)?.let { End(it, fromCourse = true) }
  }

  /** The last scheduled day across the condition's linked medications, if every course ends. */
  fun courseEnd(
    condition: Condition,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): LocalDate? {
    val medIds = medications
      .filter { it.deletedAtEpochMillis == null && it.indicationConditionId == condition.id }
      .mapTo(HashSet()) { it.id }
    if (medIds.isEmpty()) return null
    // As-needed schedules don't define a course; everything else must have a last day.
    val courses = schedules.filter {
      it.deletedAtEpochMillis == null && it.medicationId in medIds &&
        it.frequencyType != ScheduleFrequencyType.AS_NEEDED
    }
    if (courses.isEmpty()) return null
    val ends = courses.map { schedule ->
      if (schedule.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) {
        schedule.specificDatesIso.mapNotNull(::parse).maxOrNull()
      } else {
        schedule.endDateOrNull()
      } ?: return null
    }
    return ends.max()
  }

  /** Past conditions: resolved, or their (expected) end date has passed. */
  fun isArchived(
    condition: Condition,
    today: LocalDate,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): Boolean =
    condition.status == ConditionStatus.RESOLVED ||
      end(condition, medications, schedules)?.date?.isBefore(today) == true

  /** Past providers: care ended before today. */
  fun isPast(provider: Provider, today: LocalDate): Boolean =
    parse(provider.careEndDateIso)?.isBefore(today) == true

  /** A new episode of an acute condition ("had this again"): same name, notes and providers, starting [today]. */
  fun recurrence(previous: Condition, today: LocalDate): Condition = Condition(
    name = previous.name,
    details = previous.details,
    kind = previous.kind,
    status = ConditionStatus.ACTIVE,
    diagnosedDateIso = today.toString(),
    providerIds = previous.providerIds,
  )

  private fun parse(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
}
