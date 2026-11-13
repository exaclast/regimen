package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.formatNum
import java.time.LocalDate

/**
 * Reads and edits a medication's dated adjustments (titration, tapers, new strength or equipment).
 *
 * A medication's and its schedules' own fields are the starting values. Each adjustment replaces the
 * values it sets from its date on, so a day always shows what applied then and history isn't rewritten.
 * Editors show today's values; saving a change to them either records a new adjustment ([EditMode.Change])
 * or corrects the value in effect today ([EditMode.Fix]), wherever that value came from.
 */
object Adjustments {

  /** The kinds of value an adjustment can carry. */
  enum class Part(val label: String) {
    STRENGTH("strength"),
    NDC("NDC"),
    DEFAULT_DOSE("default dose"),
    EQUIPMENT("injection details"),
    DOSE("dose"),
  }

  sealed interface EditMode {
    /** It was always this: correct the value in effect today. */
    data object Fix : EditMode

    /** It changes on [dateIso]: record an adjustment. */
    data class Change(val dateIso: String, val note: String? = null) : EditMode
  }

  private val ORDER = compareBy<MedicationAdjustment>({ it.effectiveDateOrNull() }, { it.createdAtEpochMillis })

  /** Readable adjustments, oldest first. One with an unreadable date never applies. */
  fun sorted(medication: Medication): List<MedicationAdjustment> =
    medication.adjustments.filter { it.effectiveDateOrNull() != null }.sortedWith(ORDER)

  private fun inEffect(medication: Medication, date: LocalDate): List<MedicationAdjustment> =
    sorted(medication).filter { !it.effectiveDateOrNull()!!.isAfter(date) }

  /** The medication as it was (or will be) on [date]: strength, default dose and injection details. */
  fun asOf(medication: Medication, date: LocalDate): Medication {
    if (medication.adjustments.isEmpty()) return medication
    var result = medication
    inEffect(medication, date).forEach { adjustment ->
      adjustment.strength?.let { result = result.copy(strength = it) }
      adjustment.ndc11?.let { result = result.copy(ndc11 = it.takeIf { ndc -> ndc.isNotBlank() }) }
      adjustment.defaultDose?.let { result = result.copy(defaultDoseAmount = it.amount, defaultDoseUnit = it.unit) }
      // Injection details only mean something while the form is injectable.
      adjustment.injectableDetails?.takeIf { medication.form.isInjectable }?.let {
        result = result.copy(injectableDetails = it)
      }
    }
    return result
  }

  /** [schedule]'s dose per occurrence on [date]. */
  fun doseOn(medication: Medication, schedule: MedicationSchedule, date: LocalDate): DoseAmount =
    inEffect(medication, date)
      .mapNotNull { adjustment -> adjustment.scheduleDoses.lastOrNull { it.scheduleId == schedule.id } }
      .lastOrNull()
      ?.let { DoseAmount(it.amount, it.unit) }
      ?: DoseAmount(schedule.doseAmount, schedule.doseUnit)

  /** [schedule] with its dose as of [date]; same id, so logs still match it. */
  fun scheduleOn(medication: Medication, schedule: MedicationSchedule, date: LocalDate): MedicationSchedule {
    if (medication.adjustments.isEmpty()) return schedule
    val dose = doseOn(medication, schedule, date)
    return schedule.copy(doseAmount = dose.amount, doseUnit = dose.unit)
  }

  /** True when [schedule]'s dose on [date] differs from the day before: the first dose at a new amount. */
  fun doseChangesOn(medication: Medication, schedule: MedicationSchedule, date: LocalDate): Boolean =
    medication.adjustments.isNotEmpty() &&
      doseOn(medication, schedule, date) != doseOn(medication, schedule, date.minusDays(1))

  /** The first adjustment after [date], to show what's coming. */
  fun nextAfter(medication: Medication, date: LocalDate): MedicationAdjustment? =
    sorted(medication).firstOrNull { it.effectiveDateOrNull()!!.isAfter(date) }

  // --- Editing -----------------------------------------------------------------------------------

  /** Tracked values that differ between [current] (as of today) and what the editor produced. */
  fun changedParts(current: Medication, edited: Medication): Set<Part> = buildSet {
    if (current.strength.trim() != edited.strength.trim()) add(Part.STRENGTH)
    if (Medication.normalizeNdc11(current.ndc11) != Medication.normalizeNdc11(edited.ndc11)) add(Part.NDC)
    if (current.defaultDoseAmount != edited.defaultDoseAmount || current.defaultDoseUnit != edited.defaultDoseUnit) {
      add(Part.DEFAULT_DOSE)
    }
    // No details yet and the editor's blank defaults are the same thing.
    if (edited.form.isInjectable &&
      (current.injectableDetails ?: InjectableDetails()) != (edited.injectableDetails ?: InjectableDetails())
    ) {
      add(Part.EQUIPMENT)
    }
  }

  /**
   * The changes worth asking about. Filling in an NDC that was blank (after a change cleared it, or never
   * known) is never a step: it just completes the record.
   */
  fun askParts(current: Medication, edited: Medication): Set<Part> =
    changedParts(current, edited).filterNot { it == Part.NDC && current.ndc11.isNullOrBlank() }.toSet()

  /**
   * True when recording [edited] as a change would clear the NDC: the strength or the vial or device size
   * differs, so the old NDC no longer describes the product, and the user hasn't entered a new one. The
   * lookup fills it in later.
   */
  fun clearsNdc(current: Medication, edited: Medication): Boolean {
    if (current.ndc11.isNullOrBlank()) return false
    if (changedParts(current, edited).contains(Part.NDC)) return false
    val packageChanged = edited.form.isInjectable &&
      current.injectableDetails?.vialVolumeMl != edited.injectableDetails?.vialVolumeMl
    return current.strength.trim() != edited.strength.trim() || packageChanged
  }

  /**
   * Whether saving [edited] should ask "change or fix". Only when a tracked value changed, the form is the
   * same (a new form is a correction, not a step), and there's history to protect.
   */
  fun shouldAskForMedication(original: Medication?, edited: Medication, logs: List<DoseLog>, today: LocalDate): Boolean {
    if (original == null || original.form != edited.form) return false
    if (askParts(asOf(original, today), edited).isEmpty()) return false
    return original.adjustments.isNotEmpty() || logs.any { it.medicationId == original.id && it.deletedAtEpochMillis == null }
  }

  fun shouldAskForSchedule(
    medication: Medication,
    original: MedicationSchedule?,
    edited: MedicationSchedule,
    logs: List<DoseLog>,
    today: LocalDate,
  ): Boolean {
    if (original == null || !doseChanged(medication, original, edited, today)) return false
    return medication.adjustments.isNotEmpty() ||
      logs.any { it.scheduleId == original.id && it.deletedAtEpochMillis == null }
  }

  private fun doseChanged(medication: Medication, original: MedicationSchedule, edited: MedicationSchedule, today: LocalDate) =
    doseOn(medication, original, today) != DoseAmount(edited.doseAmount, edited.doseUnit)

  /**
   * Saves an editor's result. [edited] holds today's values as the user left them; untracked fields
   * (name, sites, limits…) are taken as they are.
   */
  fun applyMedicationEdit(original: Medication, edited: Medication, mode: EditMode, today: LocalDate): Medication {
    val current = asOf(original, today)
    val parts = changedParts(current, edited)
    val formChanged = original.form != edited.form
    val untouched = edited.copy(
      strength = original.strength,
      ndc11 = original.ndc11,
      defaultDoseAmount = original.defaultDoseAmount,
      defaultDoseUnit = original.defaultDoseUnit,
      injectableDetails = if (formChanged) edited.injectableDetails else original.injectableDetails,
      adjustments = original.adjustments,
    )
    if (parts.isEmpty()) return untouched
    return when {
      mode is EditMode.Change && !formChanged -> upsert(
        untouched,
        MedicationAdjustment(
          effectiveDateIso = mode.dateIso,
          note = mode.note?.trim()?.ifEmpty { null },
          strength = edited.strength.trim().takeIf { Part.STRENGTH in parts },
          ndc11 = when {
            // A new (or removed) NDC is recorded as entered; otherwise a new strength or package clears it.
            Part.NDC in parts -> Medication.normalizeNdc11(edited.ndc11).orEmpty()
            clearsNdc(current, edited) -> ""
            else -> null
          },
          defaultDose = DoseAmount(edited.defaultDoseAmount, edited.defaultDoseUnit).takeIf { Part.DEFAULT_DOSE in parts },
          injectableDetails = edited.injectableDetails.takeIf { Part.EQUIPMENT in parts },
        ),
      )
      else -> parts.fold(untouched) { med, part -> fixPart(med, part, edited, today) }
    }
  }

  /** Writes [edited]'s value for [part] into whichever layer supplies it today. */
  private fun fixPart(medication: Medication, part: Part, edited: Medication, today: LocalDate): Medication {
    val source = inEffect(medication, today).lastOrNull { adjustment ->
      when (part) {
        Part.STRENGTH -> adjustment.strength != null
        Part.NDC -> adjustment.ndc11 != null
        Part.DEFAULT_DOSE -> adjustment.defaultDose != null
        Part.EQUIPMENT -> adjustment.injectableDetails != null
        Part.DOSE -> false
      }
    }
    if (source == null) {
      return when (part) {
        Part.STRENGTH -> medication.copy(strength = edited.strength.trim())
        Part.NDC -> medication.copy(ndc11 = Medication.normalizeNdc11(edited.ndc11))
        Part.DEFAULT_DOSE -> medication.copy(defaultDoseAmount = edited.defaultDoseAmount, defaultDoseUnit = edited.defaultDoseUnit)
        Part.EQUIPMENT -> medication.copy(injectableDetails = edited.injectableDetails)
        Part.DOSE -> medication
      }
    }
    val fixed = when (part) {
      Part.STRENGTH -> source.copy(strength = edited.strength.trim())
      Part.NDC -> source.copy(ndc11 = Medication.normalizeNdc11(edited.ndc11).orEmpty())
      Part.DEFAULT_DOSE -> source.copy(defaultDose = DoseAmount(edited.defaultDoseAmount, edited.defaultDoseUnit))
      Part.EQUIPMENT -> source.copy(injectableDetails = edited.injectableDetails)
      Part.DOSE -> source
    }
    return replace(medication, fixed)
  }

  /**
   * Saves a schedule editor's result. Returns the medication when an adjustment was added or corrected
   * (null when it doesn't need saving) and the schedule to save.
   */
  fun applyScheduleEdit(
    medication: Medication,
    original: MedicationSchedule,
    edited: MedicationSchedule,
    mode: EditMode,
    today: LocalDate,
  ): Pair<Medication?, MedicationSchedule> {
    val untouched = edited.copy(doseAmount = original.doseAmount, doseUnit = original.doseUnit)
    if (!doseChanged(medication, original, edited, today)) return null to untouched
    val newDose = ScheduleDose(original.id, edited.doseAmount, edited.doseUnit)
    return when (mode) {
      is EditMode.Change -> upsert(
        medication,
        MedicationAdjustment(
          effectiveDateIso = mode.dateIso,
          note = mode.note?.trim()?.ifEmpty { null },
          scheduleDoses = listOf(newDose),
        ),
      ) to untouched
      EditMode.Fix -> {
        val source = inEffect(medication, today).lastOrNull { a -> a.scheduleDoses.any { it.scheduleId == original.id } }
        if (source == null) {
          null to untouched.copy(doseAmount = edited.doseAmount, doseUnit = edited.doseUnit)
        } else {
          replace(medication, source.copy(scheduleDoses = source.scheduleDoses.map { if (it.scheduleId == original.id) newDose else it })) to untouched
        }
      }
    }
  }

  /** Adds [adjustment], merging into one already on the same date (its set values win). */
  fun upsert(medication: Medication, adjustment: MedicationAdjustment): Medication {
    val sameDay = medication.adjustments.firstOrNull {
      it.id != adjustment.id && it.effectiveDateOrNull() != null && it.effectiveDateOrNull() == adjustment.effectiveDateOrNull()
    }
    if (sameDay == null) return replace(medication, adjustment)
    val merged = sameDay.copy(
      note = adjustment.note ?: sameDay.note,
      strength = adjustment.strength ?: sameDay.strength,
      ndc11 = adjustment.ndc11 ?: sameDay.ndc11,
      defaultDose = adjustment.defaultDose ?: sameDay.defaultDose,
      injectableDetails = adjustment.injectableDetails ?: sameDay.injectableDetails,
      scheduleDoses = sameDay.scheduleDoses.filter { old -> adjustment.scheduleDoses.none { it.scheduleId == old.scheduleId } } +
        adjustment.scheduleDoses,
    )
    return replace(medication.copy(adjustments = medication.adjustments.filter { it.id != adjustment.id }), merged)
  }

  /** Replaces the adjustment with [adjustment]'s id, or adds it. An empty one is removed instead. */
  fun replace(medication: Medication, adjustment: MedicationAdjustment): Medication {
    val others = medication.adjustments.filter { it.id != adjustment.id }
    return medication.copy(adjustments = if (adjustment.isEmpty) others else others + adjustment)
  }

  fun remove(medication: Medication, adjustmentId: String): Medication =
    medication.copy(adjustments = medication.adjustments.filter { it.id != adjustmentId })

  /** One line per value an adjustment sets, for lists. [scheduleLabel] names a schedule, or null if it's gone. */
  fun describe(adjustment: MedicationAdjustment, scheduleLabel: (String) -> String?): List<String> = buildList {
    adjustment.scheduleDoses.forEach { dose ->
      scheduleLabel(dose.scheduleId)?.let { add("$it: ${formatNum(dose.amount)} ${dose.unit.symbol}") }
    }
    adjustment.strength?.let { add("Strength: $it") }
    adjustment.ndc11?.let { add(if (it.isBlank()) "NDC cleared: look up the new one" else "NDC: $it") }
    adjustment.defaultDose?.let { add("Default dose: ${formatNum(it.amount)} ${it.unit.symbol}") }
    adjustment.injectableDetails?.let { details ->
      val parts = listOfNotNull(
        details.route.code,
        details.formattedSyringe(),
        details.formattedInjectNeedle()?.let { "needle $it" },
      )
      add("Injection: ${parts.joinToString(", ")}")
    }
  }
}
