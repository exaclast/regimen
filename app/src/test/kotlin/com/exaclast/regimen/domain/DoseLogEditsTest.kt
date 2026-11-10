package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoseLogEditsTest {

  private val zone = ZoneId.of("America/Los_Angeles")

  private fun millis(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
    LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

  private fun log(
    scheduleId: String? = null,
    status: DoseStatus = DoseStatus.TAKEN,
    scheduledDateIso: String = "2026-11-03",
    takenAt: Long = millis(3, 9, 30, second = 41),
  ) = DoseLog(
    medicationId = "med",
    scheduleId = scheduleId,
    scheduledDateIso = scheduledDateIso,
    actionTimestampMillis = takenAt,
    status = status,
    amountTaken = 1.0,
    amountUnit = DoseUnit.TABLET,
    siteUsed = "Left",
    notes = "old",
  )

  @Test
  fun anUnchangedTimeKeepsTheStoredTimeAndDay() {
    // Logged onto a past day, taken "now": editing the note mustn't move it to today.
    val pastDay = log(scheduledDateIso = "2026-11-01", takenAt = millis(3, 9, 30, second = 41))
    val edited = DoseLogEdits.edit(pastDay, newTakenAtMillis = null, amount = 1.0, site = "Left", notes = "new", zone = zone)
    assertEquals(pastDay.actionTimestampMillis, edited.actionTimestampMillis)
    assertEquals("2026-11-01", edited.scheduledDateIso)
    assertEquals("new", edited.notes)
  }

  @Test
  fun anUnscheduledDoseFollowsAChangedTimeToItsNewDay() {
    val edited = DoseLogEdits.edit(log(), millis(5, 22), amount = 2.0, site = null, notes = null, zone = zone)
    assertEquals(millis(5, 22), edited.actionTimestampMillis)
    assertEquals("2026-11-05", edited.scheduledDateIso)
    assertEquals(2.0, edited.amountTaken, 0.0)
  }

  @Test
  fun aScheduledDoseStaysOnItsOccurrenceWhenTheTimeChanges() {
    val scheduled = log(scheduleId = "s1", scheduledDateIso = "2026-11-02", takenAt = millis(3, 8))
    val edited = DoseLogEdits.edit(scheduled, millis(3, 9), amount = null, site = "Left", notes = null, zone = zone)
    assertEquals("2026-11-02", edited.scheduledDateIso)
    assertEquals(millis(3, 9), edited.actionTimestampMillis)
    // No amount given: the stored one stays.
    assertEquals(1.0, edited.amountTaken, 0.0)
  }

  @Test
  fun blankSiteAndNotesAreCleared() {
    val edited = DoseLogEdits.edit(log(), null, amount = 1.0, site = "  ", notes = "  ", zone = zone)
    assertNull(edited.siteUsed)
    assertNull(edited.notes)
  }

  @Test
  fun aSkippedDoseOnlyTakesANote() {
    val skipped = log(status = DoseStatus.SKIPPED)
    val edited = DoseLogEdits.edit(skipped, millis(9, 9), amount = 5.0, site = "Right", notes = " felt sick ", zone = zone)
    assertEquals(skipped.actionTimestampMillis, edited.actionTimestampMillis)
    assertEquals(skipped.amountTaken, edited.amountTaken, 0.0)
    assertEquals("Left", edited.siteUsed)
    assertEquals("felt sick", edited.notes)
  }
}
