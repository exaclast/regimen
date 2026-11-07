package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.TimeOfDayBucket
import kotlin.math.abs
import kotlin.math.min

/**
 * What deleting a dose window does to the schedules and logs that use it. Their doses move to a
 * window the user chooses, and logs move with them so a dose already taken isn't due again.
 */
object WindowDeletion {

  data class ScheduleChange(val before: MedicationSchedule, val after: MedicationSchedule) {
    /** The target window was already on this schedule, so it ends up with one dose a day fewer. */
    val losesDose: Boolean
      get() = after.bucketIds.size < before.bucketIds.size
  }

  data class Plan(val scheduleChanges: List<ScheduleChange>, val movedLogs: List<DoseLog>)

  fun affectedSchedules(windowId: String, schedules: List<MedicationSchedule>): List<MedicationSchedule> =
    schedules.filter { it.deletedAtEpochMillis == null && windowId in it.bucketIds }

  /** The window whose start time is closest to [window]'s, wrapping around midnight. */
  fun nearestWindow(window: TimeOfDayBucket, others: List<TimeOfDayBucket>): TimeOfDayBucket? {
    val start = window.hour * 60 + window.minute
    return others.minByOrNull { other ->
      val gap = abs(other.hour * 60 + other.minute - start)
      min(gap, 24 * 60 - gap)
    }
  }

  fun plan(
    windowId: String,
    targetWindowId: String,
    schedules: List<MedicationSchedule>,
    logs: List<DoseLog>,
    nowMillis: Long,
  ): Plan {
    val changes = affectedSchedules(windowId, schedules).map { schedule ->
      fun moved(id: String?) = if (id == windowId) targetWindowId else id
      ScheduleChange(
        before = schedule,
        after = schedule.copy(
          bucketIds = schedule.bucketIds.map { if (it == windowId) targetWindowId else it }.distinct(),
          startBucketId = moved(schedule.startBucketId),
          endBucketId = moved(schedule.endBucketId),
          updatedAtEpochMillis = nowMillis,
        ),
      )
    }

    // A slot that already has a log keeps it; the deleted window's log just stays in the history.
    val filledSlots = logs
      .filter { it.deletedAtEpochMillis == null && it.bucketId == targetWindowId }
      .mapTo(HashSet()) { it.scheduleId to it.scheduledDateIso }
    val movedLogs = logs
      .filter { it.deletedAtEpochMillis == null && it.bucketId == windowId }
      .filter { filledSlots.add(it.scheduleId to it.scheduledDateIso) }
      .map { it.copy(bucketId = targetWindowId, updatedAtEpochMillis = nowMillis) }

    return Plan(changes, movedLogs)
  }
}
