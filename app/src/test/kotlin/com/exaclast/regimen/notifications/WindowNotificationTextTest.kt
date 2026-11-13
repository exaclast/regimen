package com.exaclast.regimen.notifications

import com.exaclast.regimen.domain.PacingEvaluation
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowNotificationTextTest {

  private val bucket = TimeOfDayBucket(id = "b", name = "Bedtime", hour = 21, endHour = 23)
  private val ready = PacingEvaluation(canTakeNow = true, isIntervalWarning = false, isDailyLimitReached = false, statusSummary = "")
  private val atLimit = ready.copy(canTakeNow = false, isDailyLimitReached = true, dosesTakenToday = 1, maxDailyDoses = 1.0)

  private fun item(pacing: PacingEvaluation, site: String? = null) = ScheduledDoseItem(
    key = "s_b_2026-11-06",
    medication = Medication(
      formulaName = "Insulin Glargine (rDNA origin)",
      brandName = "Lantus",
      strength = "100 units/mL (U-100)",
      form = MedicationForm.INJECTABLE_VIAL,
      siteMode = if (site != null) SiteMode.ROTATE else SiteMode.OFF,
      sites = listOfNotNull(site),
    ),
    schedule = MedicationSchedule(medicationId = "m", doseAmount = 20.0, doseUnit = DoseUnit.UNITS),
    bucket = bucket,
    dateIso = "2026-11-06",
    existingLog = null,
    suggestedSite = site,
    lastSiteUsed = null,
    pacing = pacing,
    indications = emptyList(),
    prescriber = null,
  )

  @Test
  fun doseComesBeforeStrengthSoItSurvivesBeingCutOff() {
    assertEquals(
      listOf("Lantus — 20 units (0.2 mL) · Thigh - Left · 100 units/mL (U-100)"),
      DoseNotificationManager.windowBodyLines(listOf(item(ready, site = "Thigh - Left"))),
    )
  }

  @Test
  fun pacingLimitGetsItsOwnLine() {
    assertEquals(
      listOf("Lantus — 20 units (0.2 mL) · 100 units/mL (U-100)", "Lantus: Daily limit reached (1 of 1)"),
      DoseNotificationManager.windowBodyLines(listOf(item(atLimit))),
    )
  }
}
