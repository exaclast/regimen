package com.exaclast.regimen.domain

import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionKind
import com.exaclast.regimen.model.ConditionStatus
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

class ConditionTimelineTest {

  private val today = LocalDate.of(2026, 11, 6)
  private val sinusitis = Condition(id = "c1", name = "Sinusitis", kind = ConditionKind.ACUTE, diagnosedDateIso = "2026-11-04")
  private val amox = Medication(id = "m1", formulaName = "Amoxicillin", strength = "875 mg", indicationConditionId = "c1")
  private val course = MedicationSchedule(id = "s1", medicationId = "m1", startDateIso = "2026-11-04", endDateIso = "2026-11-13")

  @Test
  fun acuteConditionEndsWithItsMedicationCourse() {
    val end = ConditionTimeline.end(sinusitis, listOf(amox), listOf(course))
    assertEquals(LocalDate.of(2026, 11, 13), end?.date)
    assertTrue(end!!.fromCourse)
    assertFalse(ConditionTimeline.isArchived(sinusitis, today, listOf(amox), listOf(course)))
    // The day after the last dose it's archived; on the last day it's still current.
    assertFalse(ConditionTimeline.isArchived(sinusitis, LocalDate.of(2026, 11, 13), listOf(amox), listOf(course)))
    assertTrue(ConditionTimeline.isArchived(sinusitis, LocalDate.of(2026, 11, 14), listOf(amox), listOf(course)))
  }

  @Test
  fun courseEndIsTheLatestOfSeveralAndIgnoresAsNeeded() {
    val second = Medication(id = "m2", formulaName = "Prednisone", strength = "20 mg", indicationConditionId = "c1")
    val taper = MedicationSchedule(id = "s2", medicationId = "m2", endDateIso = "2026-11-20")
    val prn = MedicationSchedule(id = "s3", medicationId = "m2", frequencyType = ScheduleFrequencyType.AS_NEEDED)
    val end = ConditionTimeline.courseEnd(sinusitis, listOf(amox, second), listOf(course, taper, prn))
    assertEquals(LocalDate.of(2026, 11, 20), end)
  }

  @Test
  fun openEndedCourseOrNoMedicationsMeansNoExpectedEnd() {
    val ongoing = course.copy(endDateIso = null)
    assertNull(ConditionTimeline.end(sinusitis, listOf(amox), listOf(ongoing)))
    assertNull(ConditionTimeline.end(sinusitis, emptyList(), emptyList()))
    assertFalse(ConditionTimeline.isArchived(sinusitis, today, emptyList(), emptyList()))
  }

  @Test
  fun specificDatesScheduleEndsOnItsLastDate() {
    val dates = course.copy(frequencyType = ScheduleFrequencyType.SPECIFIC_DATES, endDateIso = null,
      specificDatesIso = setOf("2026-11-05", "2026-11-09"))
    assertEquals(LocalDate.of(2026, 11, 9), ConditionTimeline.courseEnd(sinusitis, listOf(amox), listOf(dates)))
  }

  @Test
  fun explicitEndDateWinsAndChronicNeverUsesCourses() {
    val explicit = sinusitis.copy(endDateIso = "2026-11-08")
    assertEquals(LocalDate.of(2026, 11, 8), ConditionTimeline.end(explicit, listOf(amox), listOf(course))?.date)
    val chronic = sinusitis.copy(kind = ConditionKind.CHRONIC)
    assertNull(ConditionTimeline.end(chronic, listOf(amox), listOf(course)))
  }

  @Test
  fun resolvedIsArchivedRegardlessOfDates() {
    val resolved = sinusitis.copy(status = ConditionStatus.RESOLVED)
    assertTrue(ConditionTimeline.isArchived(resolved, today, emptyList(), emptyList()))
  }

  @Test
  fun deletedMedicationsAndSchedulesDontCount() {
    val deletedCourse = course.copy(deletedAtEpochMillis = 1L)
    assertNull(ConditionTimeline.courseEnd(sinusitis, listOf(amox), listOf(deletedCourse)))
    assertNull(ConditionTimeline.courseEnd(sinusitis, listOf(amox.copy(deletedAtEpochMillis = 1L)), listOf(course)))
  }

  @Test
  fun recurrenceStartsANewActiveEpisodeWithSameProviders() {
    val past = sinusitis.copy(status = ConditionStatus.RESOLVED, endDateIso = "2026-06-10", providerIds = listOf("p1"))
    val again = ConditionTimeline.recurrence(past, today)
    assertEquals("Sinusitis", again.name)
    assertEquals(ConditionStatus.ACTIVE, again.status)
    assertEquals("2026-11-06", again.diagnosedDateIso)
    assertNull(again.endDateIso)
    assertEquals(listOf("p1"), again.providerIds)
    assertTrue(again.id != past.id)
  }

  @Test
  fun providerIsPastAfterCareEnds() {
    val provider = Provider(id = "p1", name = "Dr. A", careEndDateIso = "2026-11-05")
    assertTrue(ConditionTimeline.isPast(provider, today))
    assertFalse(ConditionTimeline.isPast(provider.copy(careEndDateIso = "2026-11-06"), today))
    assertFalse(ConditionTimeline.isPast(provider.copy(careEndDateIso = null), today))
  }
}
