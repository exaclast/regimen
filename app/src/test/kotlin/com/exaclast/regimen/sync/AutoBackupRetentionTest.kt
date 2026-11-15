package com.exaclast.regimen.sync

import com.exaclast.regimen.sync.AutoBackupRetention.BackupFileEntry
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoBackupRetentionTest {

  private fun entry(name: String, modified: Long = 0L) =
    BackupFileEntry(id = name, name = name, lastModifiedMillis = modified)

  @Test
  fun parseBackupFileName_acceptsStandardTimedAndSuffixFormsAndRejectsOthers() {
    val standard = AutoBackupRetention.parseBackupFileName("Regimen backup 2026-11-15.regimen")!!
    assertEquals(LocalDate.of(2026, 11, 15), standard.date)
    assertEquals(0, standard.timeOrder)

    val timed = AutoBackupRetention.parseBackupFileName("Regimen backup 2026-11-15 143022.regimen")!!
    assertEquals(LocalDate.of(2026, 11, 15), timed.date)
    assertEquals(143022, timed.timeOrder)

    val duplicate = AutoBackupRetention.parseBackupFileName("Regimen backup 2026-11-15 (2).regimen")!!
    assertEquals(LocalDate.of(2026, 11, 15), duplicate.date)
    assertEquals(2, duplicate.sequence)

    assertNull(AutoBackupRetention.parseBackupFileName("Regimen backup 2026-02-30.regimen"))
    assertNull(AutoBackupRetention.parseBackupFileName("Regimen backup 2026-11-15 250000.regimen"))
    assertNull(AutoBackupRetention.parseBackupFileName("notes.txt"))
    assertNull(AutoBackupRetention.parseBackupFileName("Regimen backup 2026-11-15.json"))
    assertNull(AutoBackupRetention.parseBackupFileName("other-backup-2026-11-15.regimen"))
  }

  @Test
  fun nextBackupFileName_avoidsSameDayCollisions() {
    val dt = LocalDateTime.of(2026, 11, 15, 9, 5, 30)
    assertEquals(
      "Regimen backup 2026-11-15.regimen",
      AutoBackupRetention.nextBackupFileName(emptySet(), dt),
    )
    assertEquals(
      "Regimen backup 2026-11-15 090530.regimen",
      AutoBackupRetention.nextBackupFileName(setOf("Regimen backup 2026-11-15.regimen"), dt),
    )
    assertEquals(
      "Regimen backup 2026-11-15 090530_1.regimen",
      AutoBackupRetention.nextBackupFileName(
        setOf(
          "Regimen backup 2026-11-15.regimen",
          "Regimen backup 2026-11-15 090530.regimen",
        ),
        dt,
      ),
    )
  }

  @Test
  fun planRetention_keepsLastNPlusNewestOfEachMonthForTwelveMonthsAndIgnoresNonBackupFiles() {
    val today = LocalDate.of(2026, 11, 15)
    val files = listOf(
      // Unrelated files must never appear in kept or toDelete.
      entry("notes.txt"),
      entry("Regimen backup 2026-11-15.json"),
      // Recent 3 backups (assume keepCount = 3)
      entry("Regimen backup 2026-11-15.regimen"),
      entry("Regimen backup 2026-11-14.regimen"),
      entry("Regimen backup 2026-11-13.regimen"),
      // Older November backup (same month as recent, not in top 3 -> delete)
      entry("Regimen backup 2026-11-01.regimen"),
      // October: two backups -> keep newest (2026-10-20), delete older (2026-10-05)
      entry("Regimen backup 2026-10-20.regimen"),
      entry("Regimen backup 2026-10-05.regimen"),
      // Oldest month inside 12-month window (2025-12 through 2026-11 = 12 months): keep newest
      entry("Regimen backup 2025-12-28.regimen"),
      entry("Regimen backup 2025-12-02.regimen"),
      // Outside 12-month window (2025-11 is month 13 back): delete
      entry("Regimen backup 2025-11-30.regimen"),
    )

    val plan = AutoBackupRetention.planRetention(
      files = files,
      keepCount = 3,
      monthlyWindowMonths = 12,
      today = today,
    )

    assertEquals(
      listOf(
        "Regimen backup 2026-11-15.regimen",
        "Regimen backup 2026-11-14.regimen",
        "Regimen backup 2026-11-13.regimen",
        "Regimen backup 2026-10-20.regimen",
        "Regimen backup 2025-12-28.regimen",
      ),
      plan.kept.map { it.name },
    )
    assertEquals(
      listOf(
        "Regimen backup 2026-11-01.regimen",
        "Regimen backup 2026-10-05.regimen",
        "Regimen backup 2025-12-02.regimen",
        "Regimen backup 2025-11-30.regimen",
      ),
      plan.toDelete.map { it.name },
    )
  }

  @Test
  fun planRetention_sameDayTimedBackupsOrderNewestFirst() {
    val files = listOf(
      entry("Regimen backup 2026-11-15.regimen"),
      entry("Regimen backup 2026-11-15 080000.regimen"),
      entry("Regimen backup 2026-11-15 190000.regimen"),
    )
    val plan = AutoBackupRetention.planRetention(
      files = files,
      keepCount = 2,
      monthlyWindowMonths = 12,
      today = LocalDate.of(2026, 11, 15),
    )
    assertEquals(
      listOf(
        "Regimen backup 2026-11-15 190000.regimen",
        "Regimen backup 2026-11-15 080000.regimen",
      ),
      plan.kept.map { it.name },
    )
    assertEquals(listOf("Regimen backup 2026-11-15.regimen"), plan.toDelete.map { it.name })
  }
}
