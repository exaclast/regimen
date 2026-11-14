package com.exaclast.regimen.domain

import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.AllergenType
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.Labeled
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.Texts
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
    texts: Texts,
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
      Section(texts.get(R.string.enum_allergen_drug_section), reactionLines(texts, drugReactions)),
      Section(texts.get(R.string.summary_section_other_allergies), reactionLines(texts, otherReactions)),
      Section(
        texts.get(R.string.summary_section_current),
        regimen.sortedBy { it.sortKey() }.map { regimenLine(texts, it, takenByMedication[it.id], formatTaken) },
      ),
      Section(
        texts.plural(R.plurals.summary_section_finished, RECENT_DAYS, RECENT_DAYS),
        (other + snapshot.deletedMedications).filter { it.id in takenByMedication }.sortedBy { it.sortKey() }.map { m ->
          val course = MedicationGroups.courseOf(m, snapshot.schedules, snapshot.indications, today)
          takenLine(texts, m, takenByMedication.getValue(m.id), formatTaken, course = course)
        },
      ),
      Section(
        texts.get(R.string.summary_section_indications),
        diagnosed.sortedBy { it.kind }.map { indicationLine(texts, it, snapshot.indications) },
      ),
      Section(
        texts.get(R.string.summary_section_suspected),
        suspected.map { indicationLine(texts, it, snapshot.indications) },
      ),
      Section(
        texts.plural(R.plurals.summary_section_ended, RECENT_DAYS, RECENT_DAYS),
        recentlyEnded.map { (i, date) ->
          indicationLine(texts, i, snapshot.indications).let {
            val ended = texts.get(R.string.summary_ended, formatDate(date))
            it.copy(secondary = listOfNotNull(it.secondary, ended).joinToString(", "))
          }
        },
      ),
      Section(
        texts.get(R.string.summary_section_providers),
        providers.sortedByDescending { it.isRegular }.map { providerLine(texts, it) },
      ),
    ).filter { it.lines.isNotEmpty() }

    return Summary(snapshot.settings.patientName.trim(), sections)
  }

  /** Plain text for sharing: titles, then one bullet per line. */
  fun toText(texts: Texts, summary: Summary, asOf: String): String = buildString {
    append(
      if (summary.patientName.isEmpty()) texts.get(R.string.summary_title)
      else texts.get(R.string.summary_text_title_for, summary.patientName),
    )
    append("\n").append(texts.get(R.string.summary_text_as_of, asOf))
    summary.sections.forEach { section ->
      append("\n\n${section.title}")
      section.lines.forEach { line ->
        append("\n- ${line.primary}")
        line.secondary?.let { append(" — $it") }
      }
    }
    append("\n\n").append(texts.get(R.string.summary_text_footer))
  }

  /** Its end date, or when it was last edited if it was just marked resolved without one. */
  private fun endedOn(snapshot: RegimenSnapshot, i: Indication): LocalDate? =
    snapshot.indicationEnd(i)?.date
      ?: java.time.Instant.ofEpochMilli(i.updatedAtEpochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

  private fun Medication.sortKey() = formulaName.lowercase()

  private fun Medication.title() = listOf(formulaName, strength.trim()).filter { it.isNotEmpty() }.joinToString(" ")

  private fun Texts.lower(value: Labeled) = label(value).lowercase(locale)

  private fun reactionLines(texts: Texts, reactions: List<Indication>): List<Line> =
    // The kind is spelled out so an allergy is never read as an intolerance.
    reactions.sortedBy { it.allergenType ?: AllergenType.DRUG }.map { r ->
      Line(
        primary = texts.get(R.string.summary_reaction, r.name, texts.lower(r.kind), texts.lower(r.allergenType ?: AllergenType.DRUG)),
        secondary = listOfNotNull(r.severity?.let { texts.label(it) }, r.reaction?.takeIf { it.isNotBlank() })
          .joinToString(", ").ifEmpty { null },
      )
    }

  private fun indicationLine(texts: Texts, i: Indication, all: List<Indication>): Line {
    val primaries = IndicationTimeline.primariesOf(i, all)
    return Line(
      i.name,
      listOfNotNull(
        texts.label(i.kind),
        texts.label(i.status).takeIf { i.kind.statuses.size > 2 },
        i.severity?.let { texts.get(R.string.summary_severity, texts.lower(it)) },
        primaries.takeIf { it.isNotEmpty() }?.let { texts.get(R.string.summary_secondary_to, it.joinToString { p -> p.name }) },
      ).joinToString(", "),
    )
  }

  private fun providerLine(texts: Texts, p: Provider): Line =
    Line(
      p.name,
      listOfNotNull(
        p.specialty?.takeIf { it.isNotBlank() },
        texts.get(R.string.summary_pcp).takeIf { p.isPcp },
        p.practice?.takeIf { it.isNotBlank() },
        p.phone?.takeIf { it.isNotBlank() },
      ).joinToString(", ").ifEmpty { null },
    )

  /** A regimen drug is always listed; with nothing taken lately it shows the usual dose instead. */
  private fun regimenLine(texts: Texts, m: Medication, taken: List<DoseLog>?, formatTaken: (Long) -> String): Line =
    if (taken.isNullOrEmpty()) {
      Line(
        m.title(),
        texts.plural(R.plurals.summary_none_taken, RECENT_DAYS, formatNum(m.defaultDoseAmount), m.defaultDoseUnit.symbol, RECENT_DAYS),
      )
    } else {
      takenLine(texts, m, taken, formatTaken)
    }

  private fun takenLine(
    texts: Texts,
    m: Medication,
    taken: List<DoseLog>,
    formatTaken: (Long) -> String,
    course: MedicationCourse? = null,
  ): Line {
    val last = taken.maxBy { it.actionTimestampMillis }
    val dose = texts.get(
      R.string.summary_last_taken,
      formatNum(last.amountTaken),
      last.amountUnit.symbol,
      formatTaken(last.actionTimestampMillis),
    )
    return Line(m.title(), if (course == null) dose else texts.get(R.string.summary_with_course, texts.lower(course), dose))
  }

  private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
