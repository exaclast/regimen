package com.exaclast.regimen.sync

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Pure filename parsing and retention planning for automatic local backups.
 *
 * Only files whose names match Regimen's encrypted backup pattern (`Regimen backup <date>.regimen`
 * with an optional same-day timestamp or collision suffix) are managed; unrelated files in the
 * user-chosen folder are never touched.
 */
object AutoBackupRetention {

  const val DEFAULT_KEEP_COUNT = 10
  const val DEFAULT_MONTHLY_WINDOW_MONTHS = 12
  val KEEP_COUNT_PRESETS = listOf(5, 10, 20, 30)

  private val FILE_PATTERN = Regex(
    """^Regimen backup (\d{4}-\d{2}-\d{2})(?: (\d{6})(?:_(\d+))?| \((\d+)\))?\.""" +
      Regex.escape(BackupFiles.FILE_EXTENSION) + "$",
  )
  private val TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss")

  data class BackupFileEntry(
    val id: String,
    val name: String,
    val lastModifiedMillis: Long = 0L,
  )

  data class ParsedBackupName(
    val date: LocalDate,
    val timeOrder: Int,
    val sequence: Int,
  )

  data class RetentionPlan(
    val kept: List<BackupFileEntry>,
    val toDelete: List<BackupFileEntry>,
  )

  fun parseBackupFileName(name: String): ParsedBackupName? {
    val match = FILE_PATTERN.matchEntire(name) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    val timeStr = match.groupValues[2]
    val timeOrder = if (timeStr.isNotEmpty()) {
      val hh = timeStr.substring(0, 2).toInt()
      val mm = timeStr.substring(2, 4).toInt()
      val ss = timeStr.substring(4, 6).toInt()
      if (hh !in 0..23 || mm !in 0..59 || ss !in 0..59) return null
      timeStr.toInt()
    } else {
      0
    }
    val subSeq = match.groupValues[3].toIntOrNull() ?: match.groupValues[4].toIntOrNull() ?: 0
    return ParsedBackupName(date = date, timeOrder = timeOrder, sequence = subSeq)
  }

  /**
   * Generates a collision-free `.regimen` filename for [dateTime].
   * Uses `Regimen backup YYYY-MM-DD.regimen` for the first backup on a date, and adds a
   * `HHmmss` suffix if an earlier backup from the same day is already in the folder.
   */
  fun nextBackupFileName(
    existingNames: Set<String>,
    dateTime: LocalDateTime = LocalDateTime.now(),
  ): String {
    val datePart = dateTime.toLocalDate().toString()
    val base = "Regimen backup $datePart.${BackupFiles.FILE_EXTENSION}"
    if (base !in existingNames) return base

    val timePart = dateTime.toLocalTime().format(TIME_FORMAT)
    val timed = "Regimen backup $datePart $timePart.${BackupFiles.FILE_EXTENSION}"
    if (timed !in existingNames) return timed

    var counter = 1
    while (true) {
      val candidate = "Regimen backup $datePart ${timePart}_$counter.${BackupFiles.FILE_EXTENSION}"
      if (candidate !in existingNames) return candidate
      counter++
    }
  }

  /**
   * Retains the newest [keepCount] Regimen backups plus the newest backup from each calendar
   * month in the trailing [monthlyWindowMonths]-month window ending in [today]'s month.
   */
  fun planRetention(
    files: List<BackupFileEntry>,
    keepCount: Int = DEFAULT_KEEP_COUNT,
    monthlyWindowMonths: Int = DEFAULT_MONTHLY_WINDOW_MONTHS,
    today: LocalDate = LocalDate.now(),
  ): RetentionPlan {
    val parsed = files.mapNotNull { entry ->
      parseBackupFileName(entry.name)?.let { entry to it }
    }
    if (parsed.isEmpty()) return RetentionPlan(kept = emptyList(), toDelete = emptyList())

    val sortedNewestFirst = parsed.sortedWith(
      compareByDescending<Pair<BackupFileEntry, ParsedBackupName>> { it.second.date }
        .thenByDescending { it.second.timeOrder }
        .thenByDescending { it.second.sequence }
        .thenByDescending { it.first.lastModifiedMillis }
        .thenByDescending { it.first.name },
    )

    val keepIds = mutableSetOf<String>()
    val effectiveKeepCount = keepCount.coerceAtLeast(1)
    sortedNewestFirst.take(effectiveKeepCount).forEach { keepIds.add(it.first.id) }

    if (monthlyWindowMonths > 0) {
      val cutoffMonth = YearMonth.from(today).minusMonths((monthlyWindowMonths - 1).toLong())
      val seenMonths = mutableSetOf<YearMonth>()
      for ((entry, meta) in sortedNewestFirst) {
        val month = YearMonth.from(meta.date)
        if (!month.isBefore(cutoffMonth) && seenMonths.add(month)) {
          keepIds.add(entry.id)
        }
      }
    }

    val (keptPairs, deletePairs) = sortedNewestFirst.partition { it.first.id in keepIds }
    return RetentionPlan(
      kept = keptPairs.map { it.first },
      toDelete = deletePairs.map { it.first },
    )
  }
}
