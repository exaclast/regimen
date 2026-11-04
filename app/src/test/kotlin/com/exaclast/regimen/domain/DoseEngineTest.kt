package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseEngineTest {

  private val sampleInjectable = Medication(
    id = "med_im",
    formulaName = "Testosterone Cypionate",
    strength = "200 mg/mL",
    form = MedicationForm.INJECTABLE_VIAL,
    defaultDoseAmount = 100.0,
    defaultDoseUnit = DoseUnit.MG,
    injectableDetails = InjectableDetails(
      route = InjectionRoute.IM,
      concentrationMgPerMl = 200.0,
      vialVolumeMl = 10.0,
      syringeVolumeMl = 1.0,
      drawNeedleGauge = 18,
      drawNeedleLengthInches = 1.0,
      injectNeedleGauge = 25,
      injectNeedleLengthInches = 1.0,
    ),
    siteRotationEnabled = true,
    rotationSites = listOf(
      "Left Ventrogluteal",
      "Right Ventrogluteal",
      "Left Vastus Lateralis",
      "Right Vastus Lateralis",
    ),
  )

  @Test
  fun siteRotation_continuesFromActualSiteUsedOnOverride() {
    // 1. With no prior logs, suggests the first site in rotation
    assertEquals("Left Ventrogluteal", DoseEngine.computeNextSite(sampleInjectable, emptyList()))

    // 2. Suppose user overrides and logs "Left Vastus Lateralis" (index 2)
    val logsAfterOverride = listOf(
      DoseLog(
        id = "log_1",
        medicationId = sampleInjectable.id,
        scheduledDateIso = "2026-10-24",
        actionTimestampMillis = 1000L,
        status = DoseStatus.TAKEN,
        amountTaken = 100.0,
        amountUnit = DoseUnit.MG,
        siteUsed = "Left Vastus Lateralis",
      )
    )

    // Next site must continue from "Left Vastus Lateralis" -> "Right Vastus Lateralis" (index 3)
    assertEquals(
      "Right Vastus Lateralis",
      DoseEngine.computeNextSite(sampleInjectable, logsAfterOverride)
    )

    // 3. After logging "Right Vastus Lateralis" (index 3), wraps around to index 0 ("Left Ventrogluteal")
    val logsAfterWrap = logsAfterOverride + DoseLog(
      id = "log_2",
      medicationId = sampleInjectable.id,
      scheduledDateIso = "2026-10-31",
      actionTimestampMillis = 2000L,
      status = DoseStatus.TAKEN,
      amountTaken = 100.0,
      amountUnit = DoseUnit.MG,
      siteUsed = "Right Vastus Lateralis",
    )
    assertEquals(
      "Left Ventrogluteal",
      DoseEngine.computeNextSite(sampleInjectable, logsAfterWrap)
    )
  }

  @Test
  fun pacingEvaluation_enforcesMinIntervalAndDailyLimitsFromMedicationObject() {
    val prnMed = Medication(
      id = "med_prn",
      formulaName = "Dextroamphetamine",
      strength = "5 mg",
      form = MedicationForm.TABLET,
      defaultDoseAmount = 1.0,
      defaultDoseUnit = DoseUnit.TABLET,
      minIntervalMinutes = 240, // 4 hours
      maxDailyDoses = 2.0,
    )

    val nowMillis = 10_000_000L
    val oneHourAgoMillis = nowMillis - (60L * 60_000L)

    val singleRecentLog = listOf(
      DoseLog(
        id = "log_a",
        medicationId = prnMed.id,
        scheduledDateIso = "2026-10-31",
        actionTimestampMillis = oneHourAgoMillis,
        status = DoseStatus.TAKEN,
        amountTaken = 1.0,
        amountUnit = DoseUnit.TABLET,
      )
    )

    val eval1 = DoseEngine.evaluatePacing(prnMed, singleRecentLog, nowMillis, "2026-10-31")
    assertFalse(eval1.canTakeNow)
    assertTrue(eval1.isIntervalWarning)
    assertFalse(eval1.isDailyLimitReached)
    assertEquals(180L, eval1.remainingWaitMinutes)
    assertEquals(1, eval1.dosesTakenToday)

    // After a second dose today, daily limit is reached
    val twoLogsToday = singleRecentLog + DoseLog(
      id = "log_b",
      medicationId = prnMed.id,
      scheduledDateIso = "2026-10-31",
      actionTimestampMillis = nowMillis - (300L * 60_000L),
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    val eval2 = DoseEngine.evaluatePacing(prnMed, twoLogsToday, nowMillis, "2026-10-31")
    assertTrue(eval2.isDailyLimitReached)
    assertFalse(eval2.canTakeNow)
    assertEquals(2, eval2.dosesTakenToday)
  }

  @Test
  fun relativeToLastDoseSchedule_shiftsWhenDoseTakenLate() {
    val sched = MedicationSchedule(
      id = "sched_rel",
      medicationId = sampleInjectable.id,
      frequencyType = ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE,
      intervalDays = 7,
      anchorDateIso = "2026-10-01",
    )

    // Suppose last dose was taken on 2026-10-25
    val logs = listOf(
      DoseLog(
        id = "log_rel",
        medicationId = sampleInjectable.id,
        scheduledDateIso = "2026-10-25",
        actionTimestampMillis = 1000L,
        status = DoseStatus.TAKEN,
        amountTaken = 100.0,
        amountUnit = DoseUnit.MG,
      )
    )

    // 6 days later (2026-10-31) -> not due yet
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-10-31"), logs))

    // 7 days later (2026-11-01) -> due!
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-01"), logs))
  }

  @Test
  fun injectableVolumeCalculation_computesCorrectMlFromConcentration() {
    val inj = sampleInjectable.injectableDetails!!
    assertEquals(0.5, inj.calculateVolumeMl(100.0, DoseUnit.MG)!!, 0.001)
  }

  @Test
  fun specificDatesSchedule_isDueOnlyOnSelectedDates() {
    val sched = MedicationSchedule(
      id = "sched_specific",
      medicationId = "med_ointment",
      frequencyType = ScheduleFrequencyType.SPECIFIC_DATES,
      specificDatesIso = setOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-12-01"),
    )

    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-01"), emptyList()))
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-02"), emptyList()))
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-03"), emptyList()))
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-12-01"), emptyList()))

    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-04"), emptyList()))
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-30"), emptyList()))
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-12-02"), emptyList()))
  }
}

