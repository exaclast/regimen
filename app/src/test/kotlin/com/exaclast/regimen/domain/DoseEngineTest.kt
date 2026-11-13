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
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseEngineTest {

  private val sampleInjectable = Medication(
    id = "med_im",
    formulaName = "Cyanocobalamin (Vitamin B12)",
    strength = "200 mg/mL",
    form = MedicationForm.INJECTABLE_VIAL,
    defaultDoseAmount = 100.0,
    defaultDoseUnit = DoseUnit.MG,
    injectableDetails = InjectableDetails(
      route = InjectionRoute.IM,
      vialVolumeMl = 10.0,
      syringeVolumeMl = 1.0,
      drawNeedleGauge = 18,
      drawNeedleLengthInches = 1.0,
      injectNeedleGauge = 25,
      injectNeedleLengthInches = 1.0,
    ),
    siteMode = SiteMode.ROTATE,
    sites = listOf(
      "Left Ventrogluteal",
      "Right Ventrogluteal",
      "Left Vastus Lateralis",
      "Right Vastus Lateralis",
    ),
  )

  @Test
  fun siteRotation_continuesFromActualSiteUsedOnOverride() {
    // 1. With no prior logs, suggests the first site in rotation
    assertEquals("Left Ventrogluteal", DoseEngine.suggestSite(sampleInjectable, emptyList()))

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
      DoseEngine.suggestSite(sampleInjectable, logsAfterOverride)
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
      DoseEngine.suggestSite(sampleInjectable, logsAfterWrap)
    )
  }

  private fun takenAt(site: String, at: Long, medicationId: String = sampleInjectable.id) = DoseLog(
    id = "log_$at",
    medicationId = medicationId,
    scheduledDateIso = "2026-10-24",
    actionTimestampMillis = at,
    status = DoseStatus.TAKEN,
    amountTaken = 1.0,
    amountUnit = DoseUnit.APPLICATION,
    siteUsed = site,
  )

  @Test
  fun recordingSuggestsTheLastSiteInsteadOfTheNext() {
    val patch = Medication(
      id = "med_patch",
      formulaName = "Estradiol",
      strength = "0.1 mg/day",
      form = MedicationForm.TOPICAL,
      siteMode = SiteMode.RECORD,
      sites = listOf("Left Hip", "Right Hip"),
    )
    assertEquals("Left Hip", DoseEngine.suggestSite(patch, emptyList()))
    assertEquals(
      "Right Hip",
      DoseEngine.suggestSite(patch, listOf(takenAt("Left Hip", 1000L, patch.id), takenAt("Right Hip", 2000L, patch.id))),
    )
  }

  @Test
  fun noSiteWhenSitesAreOffOrTheFormHasNone() {
    val log = listOf(takenAt("Left Ventrogluteal", 1000L))
    assertEquals(null, DoseEngine.suggestSite(sampleInjectable.copy(siteMode = SiteMode.OFF), log))
    // A tablet keeps no sites even if some were stored before its form changed.
    assertEquals(null, DoseEngine.suggestSite(sampleInjectable.copy(form = MedicationForm.TABLET), log))
    assertEquals(null, DoseEngine.suggestSite(sampleInjectable.copy(sites = emptyList()), log))
  }

  @Test
  fun removedLastSiteFallsBackToTheFirst() {
    val log = listOf(takenAt("Somewhere else", 1000L))
    assertEquals("Left Ventrogluteal", DoseEngine.suggestSite(sampleInjectable, log))
    assertEquals("Left Ventrogluteal", DoseEngine.suggestSite(sampleInjectable.copy(siteMode = SiteMode.RECORD), log))
  }

  @Test
  fun onlyInjectablesDefaultToRotating() {
    MedicationForm.entries.forEach { form ->
      val expected = if (form.isInjectable) SiteMode.ROTATE else SiteMode.OFF
      assertEquals(form.name, expected, form.defaultSiteMode)
    }
    assertFalse(MedicationForm.TABLET.hasSites)
    assertTrue(MedicationForm.TOPICAL.hasSites)
    assertTrue(MedicationForm.DROPS.hasSites)
  }

  @Test
  fun defaultSitesFollowRouteAndForm() {
    assertEquals(
      Medication.PRESET_SC_ABDOMEN_SITES,
      Medication.defaultSites(MedicationForm.INJECTABLE_PEN, InjectionRoute.SC),
    )
    assertEquals(
      Medication.PRESET_IM_GLUTE_SITES,
      Medication.defaultSites(MedicationForm.INJECTABLE_VIAL, InjectionRoute.IM),
    )
    assertEquals(Medication.PRESET_TOPICAL_SITES, Medication.defaultSites(MedicationForm.TOPICAL, null))
    assertEquals(emptyList<String>(), Medication.defaultSites(MedicationForm.TABLET, null))
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

  private fun takenLog(
    scheduledDateIso: String,
    takenAt: LocalDateTime,
    scheduleId: String? = "sched",
    bucketId: String? = null,
    amount: Double = 1.0,
    unit: DoseUnit = DoseUnit.TABLET,
  ) = DoseLog(
    medicationId = "med",
    scheduleId = scheduleId,
    bucketId = bucketId,
    scheduledDateIso = scheduledDateIso,
    actionTimestampMillis = takenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    status = DoseStatus.TAKEN,
    amountTaken = amount,
    amountUnit = unit,
  )

  private fun limitedMed(maxDoses: Double? = null, maxAmount: Double? = null, unit: DoseUnit = DoseUnit.TABLET) = Medication(
    id = "med",
    formulaName = "Limited",
    strength = "5 mg",
    defaultDoseUnit = unit,
    maxDailyDoses = maxDoses,
    maxDailyAmount = maxAmount,
  )

  private val noon: Long = LocalDateTime.of(2026, 11, 3, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  @Test
  fun dailyLimit_lateDoseCountsOnTheDayItWasTaken() {
    val lateDose = takenLog("2026-11-02", LocalDateTime.of(2026, 11, 3, 8, 0))
    val today = DoseEngine.evaluatePacing(limitedMed(maxDoses = 1.0), listOf(lateDose), noon, "2026-11-03")
    assertEquals(1, today.dosesTakenToday)
    assertTrue(today.isDailyLimitReached)
    val dayItWasScheduled = DoseEngine.evaluatePacing(limitedMed(maxDoses = 1.0), listOf(lateDose), noon, "2026-11-02")
    assertEquals(0, dayItWasScheduled.dosesTakenToday)
  }

  @Test
  fun dailyLimit_backfilledAsNeededDoseCountsOnTheDayChosen() {
    val backfilled = takenLog("2026-11-02", LocalDateTime.of(2026, 11, 3, 8, 0), scheduleId = null)
    val today = DoseEngine.evaluatePacing(limitedMed(maxDoses = 1.0), listOf(backfilled), noon, "2026-11-03")
    assertEquals(0, today.dosesTakenToday)
  }

  @Test
  fun backdatedPacing_ignoresDosesTakenAfterThatTime() {
    fun at(hour: Int) = LocalDateTime.of(2026, 11, 3, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val med = limitedMed().copy(minIntervalMinutes = 240)
    val afternoon = takenLog("2026-11-03", LocalDateTime.of(2026, 11, 3, 14, 0), scheduleId = null)
    // Logging a 10 AM dose after taking one at 2 PM: nothing came before it, so nothing is too close.
    assertTrue(DoseEngine.evaluatePacing(med, listOf(afternoon), at(10), "2026-11-03", backdated = true).canTakeNow)
    // Read as "now", the later dose would block it.
    assertFalse(DoseEngine.evaluatePacing(med, listOf(afternoon), at(10), "2026-11-03").canTakeNow)
    // An earlier dose still counts: 8 AM to 10 AM is inside the 4-hour interval.
    val morning = takenLog("2026-11-03", LocalDateTime.of(2026, 11, 3, 8, 0), scheduleId = null)
    val tooSoon = DoseEngine.evaluatePacing(med, listOf(morning, afternoon), at(10), "2026-11-03", backdated = true)
    assertFalse(tooSoon.canTakeNow)
    assertEquals(120L, tooSoon.remainingWaitMinutes)
  }

  @Test
  fun dailyAmountLimit_onlyTotalsItsOwnUnit() {
    val logs = listOf(
      takenLog("2026-11-03", LocalDateTime.of(2026, 11, 3, 8, 0), amount = 500.0, unit = DoseUnit.MG),
      takenLog("2026-11-03", LocalDateTime.of(2026, 11, 3, 9, 0), amount = 1.0, unit = DoseUnit.TABLET),
    )
    val pacing = DoseEngine.evaluatePacing(limitedMed(maxAmount = 3000.0, unit = DoseUnit.MG), logs, noon, "2026-11-03")
    assertEquals(500.0, pacing.amountTakenToday, 0.001)
  }

  @Test
  fun dailyLimit_doseTakenDuringItsOvernightWindowStaysOnItsScheduledDay() {
    val overnight = TimeOfDayBucket(id = "late", name = "Late", hour = 22, endHour = 2)
    val inWindow = takenLog("2026-11-02", LocalDateTime.of(2026, 11, 3, 1, 0), bucketId = "late")
    val afterWindow = takenLog("2026-11-02", LocalDateTime.of(2026, 11, 3, 8, 0), bucketId = "late")
    fun dosesOn(day: String, log: DoseLog) =
      DoseEngine.evaluatePacing(limitedMed(maxDoses = 1.0), listOf(log), noon, day, listOf(overnight)).dosesTakenToday
    assertEquals(0, dosesOn("2026-11-03", inWindow))
    assertEquals(1, dosesOn("2026-11-02", inWindow))
    assertEquals(1, dosesOn("2026-11-03", afterWindow))
  }

  @Test
  fun medicationsWithoutWindow_findsSchedulesWhoseWindowsAreGone() {
    val window = TimeOfDayBucket(id = "m", name = "Morning", hour = 7)
    val today = LocalDate.parse("2026-11-03")
    fun med(id: String) = Medication(id = id, formulaName = id, strength = "1 mg")
    val meds = listOf(med("orphaned"), med("fine"), med("prn"), med("ended"), med("removed"))
    val schedules = listOf(
      MedicationSchedule(medicationId = "orphaned", bucketIds = listOf("gone")),
      MedicationSchedule(medicationId = "fine", bucketIds = listOf("m", "gone")),
      MedicationSchedule(medicationId = "prn", frequencyType = ScheduleFrequencyType.AS_NEEDED, bucketIds = emptyList()),
      MedicationSchedule(medicationId = "ended", bucketIds = listOf("gone"), endDateIso = "2026-11-02"),
      MedicationSchedule(medicationId = "removed", bucketIds = listOf("gone"), deletedAtEpochMillis = 1),
    )
    assertEquals(
      listOf("orphaned"),
      DoseEngine.medicationsWithoutWindow(meds, schedules, listOf(window), today).map { it.id },
    )
  }

  private fun injectable(form: MedicationForm) = Medication(
    formulaName = "Insulin",
    strength = "200 units/mL",
    form = form,
    defaultDoseAmount = 20.0,
    defaultDoseUnit = DoseUnit.UNITS,
    injectableDetails = InjectableDetails(route = InjectionRoute.SC),
  )

  @Test
  fun prefilledInjector_showsNoCalculatedVolume() {
    assertEquals("20 units", injectable(MedicationForm.INJECTABLE_PEN).formattedDefaultDose())
  }

  @Test
  fun vial_stillShowsSyringeVolume() {
    // 200 units/mL: 20 units is 0.1 mL (10 marks on a U-100 syringe), not the 0.2 mL a U-100 guess gave.
    assertEquals("20 units (0.1 mL / 10 U)", injectable(MedicationForm.INJECTABLE_VIAL).formattedDefaultDose())
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
  fun injectableVolumeCalculation_computesCorrectMlFromStrength() {
    // The sample's strength is "200 mg/mL".
    assertEquals(0.5, sampleInjectable.doseVolumeMl(100.0, DoseUnit.MG)!!, 0.001)
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

  @Test
  fun dateRange_boundsDueDatesInclusively() {
    val sched = MedicationSchedule(
      id = "sched_course",
      medicationId = "med_abx",
      frequencyType = ScheduleFrequencyType.DAILY,
      startDateIso = "2026-11-03",
      endDateIso = "2026-11-12",
    )
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-02"), emptyList()))
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-03"), emptyList()))
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-12"), emptyList()))
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-13"), emptyList()))

    // Ongoing (no end date)
    val ongoing = sched.copy(endDateIso = null)
    assertTrue(DoseEngine.isScheduleDueOnDate(ongoing, LocalDate.parse("2027-06-01"), emptyList()))
    assertTrue(ongoing.isInDateRange(LocalDate.parse("2027-06-01")))
    assertTrue(sched.hasEndedBefore(LocalDate.parse("2026-11-13")))
    assertFalse(sched.hasEndedBefore(LocalDate.parse("2026-11-12")))
  }

  @Test
  fun dateRange_loggedDoseOutsideRangeStaysVisible() {
    val sched = MedicationSchedule(
      id = "sched_new",
      medicationId = "med_new",
      frequencyType = ScheduleFrequencyType.DAILY,
      startDateIso = "2026-11-05",
    )
    val log = DoseLog(
      id = "log_early",
      medicationId = "med_new",
      scheduleId = "sched_new",
      bucketId = "bucket_morning",
      scheduledDateIso = "2026-11-04",
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-04"), listOf(log)))
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-03"), listOf(log)))
    // A deleted log doesn't count.
    val deleted = log.copy(deletedAtEpochMillis = 1L)
    assertFalse(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-11-04"), listOf(deleted)))
  }

  @Test
  fun dateRange_ignoredForSpecificDates() {
    val sched = MedicationSchedule(
      id = "sched_dates",
      medicationId = "med_x",
      frequencyType = ScheduleFrequencyType.SPECIFIC_DATES,
      specificDatesIso = setOf("2026-10-01"),
      startDateIso = "2026-11-01",
      endDateIso = "2026-11-02",
    )
    assertTrue(DoseEngine.isScheduleDueOnDate(sched, LocalDate.parse("2026-10-01"), emptyList()))
  }

  @Test
  fun partialFirstAndLastDay_twiceDailyCourseStartedInTheEvening() {
    val morning = TimeOfDayBucket(id = "bucket_morning", name = "Morning", hour = 7, endHour = 10)
    val evening = TimeOfDayBucket(id = "bucket_evening", name = "Evening", hour = 17, endHour = 20)
    val buckets = listOf(morning, evening)
    val med = Medication(id = "med_abx", formulaName = "Amoxicillin", strength = "500 mg")
    val sched = MedicationSchedule(
      id = "sched_abx",
      medicationId = med.id,
      frequencyType = ScheduleFrequencyType.DAILY,
      bucketIds = listOf(morning.id, evening.id),
      startDateIso = "2026-11-03",
      endDateIso = "2026-11-12",
      startBucketId = evening.id,
      endBucketId = morning.id,
    )
    fun windowsOn(dateIso: String, logs: List<DoseLog> = emptyList()) = DoseEngine.buildDailyBoard(
      dateIso = dateIso,
      medications = listOf(med),
      schedules = listOf(sched),
      buckets = buckets,
      logs = logs,
      indications = emptyList(),
      providers = emptyList(),
    ).bucketGroups.filter { it.items.isNotEmpty() }.map { it.bucket.id }

    assertEquals(listOf(evening.id), windowsOn("2026-11-03")) // pickup day: evening only
    assertEquals(listOf(morning.id, evening.id), windowsOn("2026-11-07"))
    assertEquals(listOf(morning.id), windowsOn("2026-11-12")) // last day: morning only
    assertEquals(emptyList<String>(), windowsOn("2026-11-13"))

    // A dose logged anyway in a trimmed window stays visible.
    val earlyLog = DoseLog(
      medicationId = med.id,
      scheduleId = sched.id,
      bucketId = morning.id,
      scheduledDateIso = "2026-11-03",
      status = DoseStatus.TAKEN,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )
    assertEquals(listOf(morning.id, evening.id), windowsOn("2026-11-03", listOf(earlyLog)))
  }

  @Test
  fun partialDay_missingBoundaryWindowDoesNotRestrict() {
    val morning = TimeOfDayBucket(id = "m", name = "Morning", hour = 7)
    val sched = MedicationSchedule(
      medicationId = "x",
      startDateIso = "2026-11-03",
      startBucketId = "deleted_window",
    )
    assertTrue(sched.isWindowInRange(LocalDate.parse("2026-11-03"), morning, listOf(morning)))
  }
}
