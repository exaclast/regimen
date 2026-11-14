package com.exaclast.regimen.domain

import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.AllergenType
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.formatNum
import java.time.LocalDate

/**
 * A one-page summary to show a provider or first responder. Drug allergies and intolerances come first,
 * then the current regimen, then finished or as-needed medications actually taken in the past
 * [RECENT_DAYS] days, then indications (current, then ended in the same window) and providers. Built from the snapshot only, so the screen and
 * the shared text can't disagree.
 */
object CareSummary {

  const val RECENT_DAYS = 90

  data class Line(val primary: String, val secondary: String? = null)

  data class Section(val title: String, val lines: List<Line>)

  data class Summary(val patientName: String, val sections: List<Section>) {
    val isEmpty: Boolean
      get() = sections.isEmpty()
  }

  /** [formatTaken] turns the time a dose was taken into text; the UI passes the device's formatting. */
  fun build(
    snapshot: RegimenSnapshot,
    nowMillis: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(),
    formatDate: (LocalDate) -> String = { it.toString() },
    formatTaken: (Long) -> String,
  ): Summary {
    val (archived, current) = snapshot.indications.partition { snapshot.isArchived(it, today) }
    val recentlyEnded = archived
      .mapNotNull { i -> endedOn(snapshot, i)?.takeIf { !it.isBefore(today.minusDays(RECENT_DAYS.toLong())) }?.let { i to it } }
      .sortedByDescending { it.second }
    val (reactions, others) = current.partition { it.kind.isReaction }
    val (drugReactions, otherReactions) = reactions.partition { (it.allergenType ?: AllergenType.DRUG) == AllergenType.DRUG }
    val (suspected, diagnosed) = others.partition { it.suspected }
    val providers = snapshot.providers.filter { !IndicationTimeline.isPast(it, today) }

    val since = nowMillis - RECENT_DAYS * DAY_MILLIS
    val takenByMedication = snapshot.doseLogs
      .filter {
        it.deletedAtEpochMillis == null && it.status == DoseStatus.TAKEN && it.actionTimestampMillis in since..nowMillis
      }
      .groupBy { it.medicationId }
    val (regimen, other) = snapshot.medications
      .filter { it.deletedAtEpochMillis == null }
      .partition {
        val course = MedicationGroups.courseOf(it, snapshot.schedules, snapshot.indications, today)
        course == MedicationCourse.SHORT_TERM || course == MedicationCourse.ONGOING
      }

    val sections = listOf(
      Section("Drug allergies and intolerances", reactionLines(drugReactions)),
      Section("Other allergies", reactionLines(otherReactions)),
      Section("Current medications", regimen.sortedBy { it.sortKey() }.map { regimenLine(it, takenByMedication[it.id], formatTaken) }),
      Section(
        "Finished or as-needed, taken in the past $RECENT_DAYS days",
        other.filter { it.id in takenByMedication }.sortedBy { it.sortKey() }.map { m ->
          val course = MedicationGroups.courseOf(m, snapshot.schedules, snapshot.indications, today)
          takenLine(m, takenByMedication.getValue(m.id), formatTaken, label = course.displayName.lowercase())
        },
      ),
      Section("Indications", diagnosed.sortedBy { it.kind }.map { indicationLine(it, snapshot.indications) }),
      Section("Suspected, not diagnosed", suspected.map { indicationLine(it, snapshot.indications) }),
      Section(
        "Indications ended in the past $RECENT_DAYS days",
        recentlyEnded.map { (i, date) ->
          indicationLine(i, snapshot.indications).let { it.copy(secondary = listOfNotNull(it.secondary, "ended ${formatDate(date)}").joinToString(", ")) }
        },
      ),
      Section("Providers", providers.sortedByDescending { it.isRegular }.map(::providerLine)),
    ).filter { it.lines.isNotEmpty() }

    return Summary(snapshot.settings.patientName.trim(), sections)
  }

  /** Plain text for sharing: titles, then one bullet per line. */
  fun toText(summary: Summary, asOf: String): String = buildString {
    append("Health summary")
    if (summary.patientName.isNotEmpty()) append(" for ${summary.patientName}")
    append("\nAs of $asOf")
    summary.sections.forEach { section ->
      append("\n\n${section.title}")
      section.lines.forEach { line ->
        append("\n- ${line.primary}")
        line.secondary?.let { append(" — $it") }
      }
    }
    append("\n\nFrom Regimen")
  }

  /** Its end date, or when it was last edited if it was just marked resolved without one. */
  private fun endedOn(snapshot: RegimenSnapshot, i: Indication): LocalDate? =
    snapshot.indicationEnd(i)?.date
      ?: java.time.Instant.ofEpochMilli(i.updatedAtEpochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

  private fun Medication.sortKey() = formulaName.lowercase()

  private fun Medication.title() = listOf(formulaName, strength.trim()).filter { it.isNotEmpty() }.joinToString(" ")

  private fun reactionLines(reactions: List<Indication>): List<Line> =
    // The kind is spelled out so an allergy is never read as an intolerance.
    reactions.sortedBy { it.allergenType ?: AllergenType.DRUG }.map { r ->
      Line(
        primary = "${r.name} (${r.kind.displayName.lowercase()}, ${(r.allergenType ?: AllergenType.DRUG).displayName.lowercase()})",
        secondary = listOfNotNull(r.severity?.displayName, r.reaction?.takeIf { it.isNotBlank() })
          .joinToString(", ").ifEmpty { null },
      )
    }

  private fun indicationLine(i: Indication, all: List<Indication>): Line {
    val primaries = IndicationTimeline.primariesOf(i, all)
    return Line(
      i.name,
      listOfNotNull(
        i.kind.displayName,
        i.status.displayName.takeIf { i.kind.statuses.size > 2 },
        i.severity?.let { "${it.displayName.lowercase()} severity" },
        primaries.takeIf { it.isNotEmpty() }?.let { "secondary to ${it.joinToString { p -> p.name }}" },
      ).joinToString(", "),
    )
  }

  private fun providerLine(p: Provider): Line =
    Line(
      p.name,
      listOfNotNull(
        p.specialty?.takeIf { it.isNotBlank() },
        "PCP".takeIf { p.isPcp },
        p.practice?.takeIf { it.isNotBlank() },
        p.phone?.takeIf { it.isNotBlank() },
      ).joinToString(", ").ifEmpty { null },
    )

  /** A regimen drug is always listed; with nothing taken lately it shows the usual dose instead. */
  private fun regimenLine(m: Medication, taken: List<DoseLog>?, formatTaken: (Long) -> String): Line =
    if (taken.isNullOrEmpty()) {
      Line(m.title(), "${formatNum(m.defaultDoseAmount)} ${m.defaultDoseUnit.symbol}, none taken in the past $RECENT_DAYS days")
    } else {
      takenLine(m, taken, formatTaken)
    }

  private fun takenLine(m: Medication, taken: List<DoseLog>, formatTaken: (Long) -> String, label: String? = null): Line {
    val last = taken.maxBy { it.actionTimestampMillis }
    val dose = "${formatNum(last.amountTaken)} ${last.amountUnit.symbol}, last taken ${formatTaken(last.actionTimestampMillis)}"
    return Line(m.title(), if (label == null) dose else "$label: $dose")
  }

  private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
