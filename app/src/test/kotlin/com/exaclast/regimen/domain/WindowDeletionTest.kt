package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.TimeOfDayBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowDeletionTest {

  private val morning = TimeOfDayBucket(id = "morning", name = "Morning", hour = 7, endHour = 10)
  private val evening = TimeOfDayBucket(id = "evening", name = "Evening", hour = 17, endHour = 20)
  private val bedtime = TimeOfDayBucket(id = "bedtime", name = "Bedtime", hour = 21, endHour = 23)
  private val earlyHours = TimeOfDayBucket(id = "early", name = "Early", hour = 1, endHour = 3)

  private fun schedule(id: String, vararg windows: String) =
    MedicationSchedule(id = id, medicationId = "med-$id", bucketIds = windows.toList())

  private fun log(scheduleId: String, windowId: String, date: String = "2026-11-05", status: DoseStatus = DoseStatus.TAKEN) =
    DoseLog(
      id = "$scheduleId-$windowId-$date",
      medicationId = "med-$scheduleId",
      scheduleId = scheduleId,
      bucketId = windowId,
      scheduledDateIso = date,
      status = status,
      amountTaken = 1.0,
      amountUnit = DoseUnit.TABLET,
    )

  private fun plan(target: String, schedules: List<MedicationSchedule>, logs: List<DoseLog> = emptyList()) =
    WindowDeletion.plan("bedtime", target, schedules, logs, nowMillis = 500)

  @Test
  fun singleWindowScheduleMovesToTheChosenWindow() {
    val change = plan("morning", listOf(schedule("a", "bedtime"))).scheduleChanges.single()
    assertEquals(listOf("morning"), change.after.bucketIds)
    assertFalse(change.losesDose)
    assertEquals(500L, change.after.updatedAtEpochMillis)
  }

  @Test
  fun twiceDailyKeepsBothDosesWhenMovedToAFreeWindow() {
    val change = plan("evening", listOf(schedule("a", "morning", "bedtime"))).scheduleChanges.single()
    assertEquals(listOf("morning", "evening"), change.after.bucketIds)
    assertFalse(change.losesDose)
  }

  @Test
  fun movingIntoAWindowAlreadyOnTheScheduleIsReportedAsALostDose() {
    val change = plan("morning", listOf(schedule("a", "morning", "bedtime"))).scheduleChanges.single()
    assertEquals(listOf("morning"), change.after.bucketIds)
    assertTrue(change.losesDose)
  }

  @Test
  fun unrelatedAndDeletedSchedulesAreLeftAlone() {
    val deleted = schedule("b", "bedtime").copy(deletedAtEpochMillis = 1)
    assertTrue(plan("morning", listOf(schedule("a", "morning"), deleted)).scheduleChanges.isEmpty())
  }

  @Test
  fun firstAndLastDoseBoundariesFollowTheMove() {
    val partial = schedule("a", "morning", "bedtime").copy(startBucketId = "bedtime", endBucketId = "morning")
    val after = plan("evening", listOf(partial)).scheduleChanges.single().after
    assertEquals("evening", after.startBucketId)
    assertEquals("morning", after.endBucketId)
  }

  @Test
  fun takenDoseMovesWithItsWindowSoItIsNotDueAgain() {
    val moved = plan("morning", listOf(schedule("a", "bedtime")), listOf(log("a", "bedtime"))).movedLogs.single()
    assertEquals("morning", moved.bucketId)
    assertEquals(DoseStatus.TAKEN, moved.status)
    assertEquals(500L, moved.updatedAtEpochMillis)
  }

  @Test
  fun aSlotThatAlreadyHasALogKeepsIt() {
    val logs = listOf(log("a", "morning"), log("a", "bedtime"), log("a", "bedtime", date = "2026-11-04"))
    val moved = plan("morning", listOf(schedule("a", "morning", "bedtime")), logs).movedLogs
    assertEquals(listOf("2026-11-04"), moved.map { it.scheduledDateIso })
  }

  @Test
  fun deletedLogsDoNotMove() {
    val tombstone = log("a", "bedtime").copy(deletedAtEpochMillis = 1)
    assertTrue(plan("morning", listOf(schedule("a", "bedtime")), listOf(tombstone)).movedLogs.isEmpty())
  }

  @Test
  fun nearestWindowComparesStartTimes() {
    assertEquals(evening, WindowDeletion.nearestWindow(bedtime, listOf(morning, evening)))
  }

  @Test
  fun nearestWindowWrapsAroundMidnight() {
    assertEquals(earlyHours, WindowDeletion.nearestWindow(bedtime, listOf(evening.copy(hour = 15), earlyHours)))
  }
}
