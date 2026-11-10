package com.exaclast.regimen.domain

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import java.time.Instant
import java.time.ZoneId

/** Applying a user's corrections to a logged dose. */
object DoseLogEdits {

  /**
   * [newTakenAtMillis] is null when the time wasn't changed, so the stored time (with its seconds) and
   * day are left alone. Only a taken dose has a time, amount and site to correct; a skipped one only has a
   * note. A scheduled dose stays on the occurrence it was for. An unscheduled one belongs to the day it
   * was taken, so it follows a changed time, but one logged onto a past day keeps that day if only its
   * note is edited.
   */
  fun edit(
    log: DoseLog,
    newTakenAtMillis: Long?,
    amount: Double?,
    site: String?,
    notes: String?,
    zone: ZoneId = ZoneId.systemDefault(),
  ): DoseLog {
    val trimmedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
    if (log.status != DoseStatus.TAKEN) return log.copy(notes = trimmedNotes)

    val millis = newTakenAtMillis ?: log.actionTimestampMillis
    val moved = millis != log.actionTimestampMillis
    return log.copy(
      actionTimestampMillis = millis,
      scheduledDateIso = if (moved && log.scheduleId == null) {
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()
      } else {
        log.scheduledDateIso
      },
      amountTaken = amount ?: log.amountTaken,
      siteUsed = site?.trim()?.takeIf { it.isNotEmpty() },
      notes = trimmedNotes,
    )
  }
}
