package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.ScheduleFrequencyType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicationTimelineTest {

  private val today = LocalDate.of(2026, 11, 6)
  private val sinusitis = Indication(id = "c1", name = "Sinusitis", kind = IndicationKind.ACUTE, diagnosedDateIso = "2026-11-04")
  private val amox = Medication(id = "m1", formulaName = "Amoxicillin", strength = "875 mg", indicationIds = listOf("c1"))
  private val course = MedicationSchedule(id = "s1", medicationId = "m1", startDateIso = "2026-11-04", endDateIso = "2026-11-13")

  @Test
  fun acuteIndicationEndsWithItsMedicationCourse() {
    val end = IndicationTimeline.end(sinusitis, listOf(amox), listOf(course))
    assertEquals(LocalDate.of(2026, 11, 13), end?.date)
    assertTrue(end!!.fromCourse)
    assertFalse(IndicationTimeline.isArchived(sinusitis, today, listOf(amox), listOf(course)))
    // The day after the last dose it's archived; on the last day it's still current.
    assertFalse(IndicationTimeline.isArchived(sinusitis, LocalDate.of(2026, 11, 13), listOf(amox), listOf(course)))
    assertTrue(IndicationTimeline.isArchived(sinusitis, LocalDate.of(2026, 11, 14), listOf(amox), listOf(course)))
  }

  @Test
  fun courseEndIsTheLatestOfSeveralAndIgnoresAsNeeded() {
    val second = Medication(id = "m2", formulaName = "Prednisone", strength = "20 mg", indicationIds = listOf("c1"))
    val taper = MedicationSchedule(id = "s2", medicationId = "m2", endDateIso = "2026-11-20")
    val prn = MedicationSchedule(id = "s3", medicationId = "m2", frequencyType = ScheduleFrequencyType.AS_NEEDED)
    val end = IndicationTimeline.courseEnd(sinusitis, listOf(amox, second), listOf(course, taper, prn))
    assertEquals(LocalDate.of(2026, 11, 20), end)
  }

  @Test
  fun oneMedicationCanSetTheEndOfSeveralIndications() {
    val bronchitis = sinusitis.copy(id = "c2", name = "Bronchitis")
    val both = amox.copy(indicationIds = listOf("c1", "c2"))
    val expected = LocalDate.of(2026, 11, 13)
    assertEquals(expected, IndicationTimeline.courseEnd(sinusitis, listOf(both), listOf(course)))
    assertEquals(expected, IndicationTimeline.courseEnd(bronchitis, listOf(both), listOf(course)))
    // Linked only to sinusitis: bronchitis has no course of its own.
    assertNull(IndicationTimeline.courseEnd(bronchitis, listOf(amox), listOf(course)))
  }

  @Test
  fun preventiveEndsWithAFiniteCourseButNotAnOpenEndedOne() {
    val prevention = sinusitis.copy(kind = IndicationKind.PREVENTIVE)
    // PEP: a fixed course, so it archives itself.
    assertEquals(LocalDate.of(2026, 11, 13), IndicationTimeline.end(prevention, listOf(amox), listOf(course))?.date)
    assertTrue(IndicationTimeline.isArchived(prevention, LocalDate.of(2026, 11, 14), listOf(amox), listOf(course)))
    // PrEP: no last day, so it stays current.
    val ongoing = course.copy(endDateIso = null)
    assertNull(IndicationTimeline.end(prevention, listOf(amox), listOf(ongoing)))
    assertFalse(IndicationTimeline.isArchived(prevention, LocalDate.of(2027, 6, 1), listOf(amox), listOf(ongoing)))
  }

  @Test
  fun onlyPreventiveCallsResolvedCompleted() {
    assertEquals("Completed", IndicationKind.PREVENTIVE.statusLabel(IndicationStatus.RESOLVED))
    assertEquals("Resolved", IndicationKind.ACUTE.statusLabel(IndicationStatus.RESOLVED))
    assertEquals("Active", IndicationKind.PREVENTIVE.statusLabel(IndicationStatus.ACTIVE))
  }

  @Test
  fun groupsAreAcuteThenChronicThenPreventiveAndSkipEmptyKinds() {
    val prep = sinusitis.copy(id = "c2", name = "PrEP", kind = IndicationKind.PREVENTIVE)
    val adhd = sinusitis.copy(id = "c3", name = "ADHD", kind = IndicationKind.CHRONIC)
    val bronchitis = sinusitis.copy(id = "c4", name = "Bronchitis")
    val groups = IndicationTimeline.groupedByKind(listOf(prep, sinusitis, adhd, bronchitis))
    assertEquals(listOf(IndicationKind.ACUTE, IndicationKind.CHRONIC, IndicationKind.PREVENTIVE), groups.map { it.first })
    assertEquals(listOf("Bronchitis", "Sinusitis"), groups.first().second.map { it.name })
    assertEquals(listOf(IndicationKind.CHRONIC), IndicationTimeline.groupedByKind(listOf(adhd)).map { it.first })
  }

  @Test
  fun openEndedCourseOrNoMedicationsMeansNoExpectedEnd() {
    val ongoing = course.copy(endDateIso = null)
    assertNull(IndicationTimeline.end(sinusitis, listOf(amox), listOf(ongoing)))
    assertNull(IndicationTimeline.end(sinusitis, emptyList(), emptyList()))
    assertFalse(IndicationTimeline.isArchived(sinusitis, today, emptyList(), emptyList()))
  }

  @Test
  fun specificDatesScheduleEndsOnItsLastDate() {
    val dates = course.copy(frequencyType = ScheduleFrequencyType.SPECIFIC_DATES, endDateIso = null,
      specificDatesIso = setOf("2026-11-05", "2026-11-09"))
    assertEquals(LocalDate.of(2026, 11, 9), IndicationTimeline.courseEnd(sinusitis, listOf(amox), listOf(dates)))
  }

  @Test
  fun explicitEndDateWinsAndChronicNeverUsesCourses() {
    val explicit = sinusitis.copy(endDateIso = "2026-11-08")
    assertEquals(LocalDate.of(2026, 11, 8), IndicationTimeline.end(explicit, listOf(amox), listOf(course))?.date)
    val chronic = sinusitis.copy(kind = IndicationKind.CHRONIC)
    assertNull(IndicationTimeline.end(chronic, listOf(amox), listOf(course)))
  }

  @Test
  fun resolvedIsArchivedRegardlessOfDates() {
    val resolved = sinusitis.copy(status = IndicationStatus.RESOLVED)
    assertTrue(IndicationTimeline.isArchived(resolved, today, emptyList(), emptyList()))
  }

  @Test
  fun deletedMedicationsAndSchedulesDontCount() {
    val deletedCourse = course.copy(deletedAtEpochMillis = 1L)
    assertNull(IndicationTimeline.courseEnd(sinusitis, listOf(amox), listOf(deletedCourse)))
    assertNull(IndicationTimeline.courseEnd(sinusitis, listOf(amox.copy(deletedAtEpochMillis = 1L)), listOf(course)))
  }

  @Test
  fun recurrenceStartsANewActiveEpisodeWithSameProviders() {
    val past = sinusitis.copy(status = IndicationStatus.RESOLVED, endDateIso = "2026-06-10", providerIds = listOf("p1"))
    val again = IndicationTimeline.recurrence(past, today)
    assertEquals("Sinusitis", again.name)
    assertEquals(IndicationStatus.ACTIVE, again.status)
    assertEquals("2026-11-06", again.diagnosedDateIso)
    assertNull(again.endDateIso)
    assertEquals(listOf("p1"), again.providerIds)
    assertTrue(again.id != past.id)
  }

  @Test
  fun providerIsPastAfterCareEnds() {
    val provider = Provider(id = "p1", name = "Dr. A", careEndDateIso = "2026-11-05")
    assertTrue(IndicationTimeline.isPast(provider, today))
    assertFalse(IndicationTimeline.isPast(provider.copy(careEndDateIso = "2026-11-06"), today))
    assertFalse(IndicationTimeline.isPast(provider.copy(careEndDateIso = null), today))
  }
}
