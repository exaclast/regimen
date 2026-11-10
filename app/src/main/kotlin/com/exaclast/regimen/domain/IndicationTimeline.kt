package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate

/**
 * When indications (and providers) end, and whether they're archived ("past").
 *
 * An acute or preventive indication with no end date ends with its medication course: the last
 * scheduled day of the medications linked to it, so an infection archives itself when the
 * antibiotics finish, and so does PEP. If any linked course is open-ended, as with PrEP (or there
 * are none), there's no expected end.
 */
object IndicationTimeline {

  /** How the end date was determined, for display ("Ends Nov 15 with Amoxicillin"). */
  data class End(val date: LocalDate, val fromCourse: Boolean)

  fun end(
    indication: Indication,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): End? {
    parse(indication.endDateIso)?.let { return End(it, fromCourse = false) }
    if (!indication.kind.endsWithCourse) return null
    return courseEnd(indication, medications, schedules)?.let { End(it, fromCourse = true) }
  }

  /** The last scheduled day across the indication's linked medications, if every course ends. */
  fun courseEnd(
    indication: Indication,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): LocalDate? {
    val medIds = medications
      .filter { it.deletedAtEpochMillis == null && indication.id in it.indicationIds }
      .mapTo(HashSet()) { it.id }
    if (medIds.isEmpty()) return null
    // As-needed schedules don't define a course; everything else must have a last day.
    val courses = schedules.filter {
      it.deletedAtEpochMillis == null && it.medicationId in medIds &&
        it.frequencyType != ScheduleFrequencyType.AS_NEEDED
    }
    if (courses.isEmpty()) return null
    return courses.map { it.lastDayOrNull() ?: return null }.max()
  }

  /** Past indications: resolved, or their (expected) end date has passed. */
  fun isArchived(
    indication: Indication,
    today: LocalDate,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
  ): Boolean =
    indication.status == IndicationStatus.RESOLVED ||
      end(indication, medications, schedules)?.date?.isBefore(today) == true

  /** Past providers: care ended before today. */
  fun isPast(provider: Provider, today: LocalDate): Boolean =
    parse(provider.careEndDateIso)?.isBefore(today) == true

  /** Display order for grouped lists, by name within a kind, skipping kinds with nothing in them. */
  fun groupedByKind(indications: List<Indication>): List<Pair<IndicationKind, List<Indication>>> =
    IndicationKind.entries.mapNotNull { kind ->
      indications.filter { it.kind == kind }
        .sortedBy { it.name.lowercase() }
        .takeIf { it.isNotEmpty() }
        ?.let { kind to it }
    }

  /** A new episode of a past indication ("had this again"): same name, notes and providers, starting [today]. */
  fun recurrence(previous: Indication, today: LocalDate): Indication = Indication(
    name = previous.name,
    details = previous.details,
    kind = previous.kind,
    status = IndicationStatus.ACTIVE,
    diagnosedDateIso = today.toString(),
    providerIds = previous.providerIds,
  )

  private fun parse(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
}
