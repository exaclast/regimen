package com.exaclast.regimen.sync

import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.PrimaryClaim
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.SharedSettings
import com.exaclast.regimen.model.SyncDevice

/** A record both sides changed (or a dose both sides logged), and which version was kept. */
data class MergeConflict(
  val kind: String,
  val id: String,
  val keptLocal: Boolean,
  val description: String,
)

data class MergeResult(
  val bundle: RegimenBackupBundle,
  val conflicts: List<MergeConflict>,
  /** The merged data differs from this device's: apply it locally. */
  val localChanged: Boolean,
  /** The merged data differs from the remote copy: upload it. */
  val remoteChanged: Boolean,
)

/**
 * Three-way merge of this device's data with the shared remote copy, against [base], the copy
 * both last agreed on (null on a first sync or a file import).
 *
 * Per record:
 * - Changed on one side only → that change, from whichever device. A record missing on one side
 *   but present in [base] was deleted there (dose windows are hard-deleted).
 * - Changed on both sides → a conflict. The primary device keeps its own version; any other
 *   device defers to the remote copy. Every conflict involving the primary therefore resolves in
 *   its favor as soon as it syncs, without tracking which device made each edit. With no primary
 *   claimed yet, the newer edit wins.
 *
 * Then, two devices that each logged the same scheduled dose (different log ids, same slot) keep
 * one log; the other is tombstoned so it syncs as deleted. What was done to the dose decides first
 * (taken, then skipped, then snoozed), then the same precedence: losing a real "taken" record to a
 * newer snooze would invite a second dose.
 */
object BundleMerger {

  private enum class Side { LOCAL, REMOTE }

  fun merge(
    base: RegimenBackupBundle?,
    local: RegimenBackupBundle,
    remote: RegimenBackupBundle,
    localDeviceId: String,
    nowMillis: Long = System.currentTimeMillis(),
  ): MergeResult {
    val primary = listOfNotNull(base?.primary, local.primary, remote.primary)
      .fold(null as PrimaryClaim?) { best, claim -> if (claim.supersedes(best)) claim else best }
    val localIsPrimary = primary?.deviceId == localDeviceId

    fun <T> decide(l: T?, r: T?, updatedAt: (T) -> Long): Side = when {
      localIsPrimary -> Side.LOCAL
      primary != null -> Side.REMOTE
      l == null -> Side.REMOTE
      r == null -> Side.LOCAL
      updatedAt(l) > updatedAt(r) -> Side.LOCAL
      else -> Side.REMOTE
    }

    val conflicts = mutableListOf<MergeConflict>()

    fun <T : Any> mergeRecords(
      kind: String,
      baseList: List<T>?,
      localList: List<T>,
      remoteList: List<T>,
      id: (T) -> String,
      updatedAt: (T) -> Long,
      /** Decides a conflict before the usual precedence, when the record type requires it. */
      overrideConflict: (local: T, remote: T) -> Side? = { _, _ -> null },
    ): List<T> {
      fun choose(lv: T?, rv: T?): Side =
        (if (lv != null && rv != null) overrideConflict(lv, rv) else null) ?: decide(lv, rv, updatedAt)

      val b = baseList?.associateBy(id)
      val l = localList.associateBy(id)
      val r = remoteList.associateBy(id)
      val ids = LinkedHashSet<String>().apply {
        addAll(l.keys)
        addAll(r.keys)
        b?.keys?.let(::addAll)
      }
      return ids.mapNotNull { key ->
        val lv = l[key]
        val rv = r[key]
        when {
          lv == rv -> lv
          b == null -> when {
            // No common ancestor: absence means "never had it", not "deleted".
            lv == null -> rv
            rv == null -> lv
            else -> if (choose(lv, rv) == Side.LOCAL) lv else rv
          }
          lv == b[key] -> rv
          rv == b[key] -> lv
          else -> {
            val side = choose(lv, rv)
            conflicts += MergeConflict(
              kind = kind,
              id = key,
              keptLocal = side == Side.LOCAL,
              description = "$kind edited on two devices",
            )
            if (side == Side.LOCAL) lv else rv
          }
        }
      }
    }

    val medications = mergeRecords(
      "Medication", base?.medications, local.medications, remote.medications,
      { it.id }, { it.updatedAtEpochMillis },
    )
    val providers = mergeRecords(
      "Provider", base?.providers, local.providers, remote.providers,
      { it.id }, { it.updatedAtEpochMillis },
    )
    val conditions = mergeRecords(
      "Condition", base?.conditions, local.conditions, remote.conditions,
      { it.id }, { it.updatedAtEpochMillis },
    )
    val buckets = mergeRecords(
      "Dose window", base?.buckets, local.buckets, remote.buckets,
      { it.id }, { it.updatedAtEpochMillis },
    )
    val schedules = mergeRecords(
      "Schedule", base?.schedules, local.schedules, remote.schedules,
      { it.id }, { it.updatedAtEpochMillis },
    )
    val mergedLogs = mergeRecords(
      "Dose log", base?.doseLogs, local.doseLogs, remote.doseLogs,
      { it.id }, { it.updatedAtEpochMillis }, ::snoozeNeverOverwrites,
    )

    val localLogIds = local.doseLogs.mapTo(HashSet()) { it.id }
    val remoteLogIds = remote.doseLogs.mapTo(HashSet()) { it.id }
    val doseLogs = resolveDuplicateSlots(
      logs = mergedLogs,
      preferred = when {
        localIsPrimary -> localLogIds
        primary != null -> remoteLogIds
        else -> null
      },
      nowMillis = nowMillis,
      medicationName = { id -> medications.firstOrNull { it.id == id }?.primaryName ?: "A dose" },
      conflicts = conflicts,
      keptLocal = { it in localLogIds },
    )

    val shared = mergeRecords(
      "Settings",
      base?.let { listOf(it.sharedSettings) },
      listOf(local.sharedSettings),
      listOf(remote.sharedSettings),
      { "shared" }, { it.updatedAtEpochMillis },
    ).firstOrNull() ?: SharedSettings()

    val merged = RegimenBackupBundle(
      version = RegimenBackupBundle.CURRENT_VERSION,
      exportedAtEpochMillis = nowMillis,
      sourceDeviceId = localDeviceId,
      settings = local.settings,
      sharedSettings = shared,
      devices = mergeDevices(local.devices + remote.devices),
      primary = primary,
      medications = medications,
      providers = providers,
      conditions = conditions,
      buckets = buckets,
      schedules = schedules,
      doseLogs = doseLogs,
    )
    return MergeResult(
      bundle = merged,
      conflicts = conflicts,
      localChanged = !sameContent(merged, local),
      remoteChanged = !sameContent(merged, remote),
    )
  }

  /** The same log snoozed on one device and completed on another stays completed. */
  private fun snoozeNeverOverwrites(local: DoseLog, remote: DoseLog): Side? {
    if (local.deletedAtEpochMillis != null || remote.deletedAtEpochMillis != null) return null
    return when {
      local.status == DoseStatus.SNOOZED && remote.status != DoseStatus.SNOOZED -> Side.REMOTE
      remote.status == DoseStatus.SNOOZED && local.status != DoseStatus.SNOOZED -> Side.LOCAL
      else -> null
    }
  }

  private fun statusRank(status: DoseStatus): Int = when (status) {
    DoseStatus.TAKEN -> 2
    DoseStatus.SKIPPED -> 1
    DoseStatus.SNOOZED -> 0
  }

  /**
   * Keeps one live log per scheduled dose slot: the strongest status first, then [preferred], the
   * side whose logs win (null = no primary: the most recently updated wins).
   */
  private fun resolveDuplicateSlots(
    logs: List<DoseLog>,
    preferred: Set<String>?,
    nowMillis: Long,
    medicationName: (String) -> String,
    conflicts: MutableList<MergeConflict>,
    keptLocal: (String) -> Boolean,
  ): List<DoseLog> {
    val losers = HashSet<String>()
    logs
      .filter { it.deletedAtEpochMillis == null && it.scheduleId != null && it.bucketId != null }
      .groupBy { listOf(it.medicationId, it.scheduleId, it.bucketId, it.scheduledDateIso) }
      .values
      .filter { it.size > 1 }
      .forEach { group ->
        val winner = group.maxWith(
          compareBy<DoseLog>(
            { statusRank(it.status) },
            { preferred?.contains(it.id) == true },
            { it.updatedAtEpochMillis },
            { it.id },
          ),
        )
        group.filter { it.id != winner.id }.forEach { losers += it.id }
        conflicts += MergeConflict(
          kind = "Dose log",
          id = winner.id,
          keptLocal = keptLocal(winner.id),
          description = "${medicationName(winner.medicationId)} was logged on two devices for " +
            winner.scheduledDateIso,
        )
      }
    if (losers.isEmpty()) return logs
    return logs.map {
      if (it.id in losers) it.copy(deletedAtEpochMillis = nowMillis, updatedAtEpochMillis = nowMillis) else it
    }
  }

  private fun mergeDevices(devices: List<SyncDevice>): List<SyncDevice> =
    devices.groupBy { it.id }.values.map { copies ->
      val latest = copies.maxBy { it.lastSyncedEpochMillis }
      latest.copy(firstSeenEpochMillis = copies.minOf { it.firstSeenEpochMillis })
    }.sortedBy { it.firstSeenEpochMillis }

  /** Same shared data, ignoring export metadata and the exporting device's own settings. */
  fun sameContent(a: RegimenBackupBundle, b: RegimenBackupBundle): Boolean =
    a.sharedSettings == b.sharedSettings &&
      a.primary == b.primary &&
      a.devices.toSet() == b.devices.toSet() &&
      a.medications.toSet() == b.medications.toSet() &&
      a.providers.toSet() == b.providers.toSet() &&
      a.conditions.toSet() == b.conditions.toSet() &&
      a.buckets.toSet() == b.buckets.toSet() &&
      a.schedules.toSet() == b.schedules.toSet() &&
      a.doseLogs.toSet() == b.doseLogs.toSet()
}
