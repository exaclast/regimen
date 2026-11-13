package com.exaclast.regimen.model

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class DoseAmount(val amount: Double, val unit: DoseUnit)

/** A schedule's dose per occurrence from an adjustment's date on. */
@Serializable
data class ScheduleDose(val scheduleId: String, val amount: Double, val unit: DoseUnit)

/**
 * A dated change to a medication: a titration step, a taper, a new strength, or new equipment. From
 * [effectiveDateIso] on, each value that's set replaces the one before it; unset values carry over. The
 * medication's and schedules' own fields are the starting values, so past days keep what applied then.
 * See `domain/Adjustments.kt` for how they're read and edited.
 */
@Serializable
data class MedicationAdjustment(
  val id: String = UUID.randomUUID().toString(),
  val effectiveDateIso: String,
  val note: String? = null,
  val strength: String? = null,
  /**
   * The NDC from this date on. A different strength or package has a different NDC, so a change to either
   * clears it: blank means "no NDC from this date" until the FDA lookup fills it in. Null carries the
   * earlier one over.
   */
  val ndc11: String? = null,
  val defaultDose: DoseAmount? = null,
  /** The whole set of injection details from this date on (route, vial, syringe, needles). */
  val injectableDetails: InjectableDetails? = null,
  val scheduleDoses: List<ScheduleDose> = emptyList(),
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
  fun effectiveDateOrNull(): LocalDate? = runCatching { LocalDate.parse(effectiveDateIso.trim()) }.getOrNull()

  /** Nothing to change: no values set. */
  val isEmpty: Boolean
    get() = strength == null && ndc11 == null && defaultDose == null && injectableDetails == null && scheduleDoses.isEmpty()
}
