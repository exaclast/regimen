package com.exaclast.regimen.sync

import com.exaclast.regimen.model.RegimenBackupBundle
import kotlinx.serialization.json.Json

/** Reading, previewing and merging backup files. Pure logic; storage and keys live in the data layer. */
object BackupFiles {

  const val FILE_EXTENSION = "regimen"
  const val MIME_TYPE = "application/octet-stream"

  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val prettyJson = Json(json) { prettyPrint = true }

  fun encode(bundle: RegimenBackupBundle, pretty: Boolean = false): ByteArray =
    (if (pretty) prettyJson else json).encodeToString(RegimenBackupBundle.serializer(), bundle).toByteArray(Charsets.UTF_8)

  fun decodeBundle(plaintext: ByteArray): RegimenBackupBundle {
    val bundle = try {
      json.decodeFromString(RegimenBackupBundle.serializer(), String(plaintext, Charsets.UTF_8))
    } catch (e: Exception) {
      throw BundleCrypto.NotABackupException()
    }
    if (bundle.version > RegimenBackupBundle.CURRENT_VERSION) {
      throw BundleCrypto.UnsupportedFormatException(bundle.version)
    }
    return bundle
  }

  /** What a picked file turned out to be. */
  sealed interface Opened {
    /** Encrypted: needs the device's key, the passphrase, or the recovery code. */
    data class Encrypted(val file: ByteArray, val header: BundleCrypto.Header) : Opened
    /** Unencrypted JSON (a plain export). */
    data class Plain(val bundle: RegimenBackupBundle) : Opened
  }

  fun open(file: ByteArray): Opened =
    if (BundleCrypto.isEncryptedBackup(file)) {
      Opened.Encrypted(file, BundleCrypto.readHeader(file))
    } else {
      Opened.Plain(decodeBundle(file))
    }

  /** Per record type: how the file compares with this device. */
  data class KindDiff(
    val label: String,
    /** Live records in the file. */
    val inFile: Int,
    /** In the file but not on this device. */
    val new: Int,
    /** On both, with different contents. */
    val changed: Int,
    /** Live on this device but not in the file (kept by Merge, removed by Replace). */
    val onlyOnDevice: Int,
  )

  data class Preview(
    val exportedAtEpochMillis: Long,
    val sourceDeviceName: String?,
    val version: Int,
    val kinds: List<KindDiff>,
    /** Range of dose dates in the file's history, if any. */
    val firstDoseDateIso: String?,
    val lastDoseDateIso: String?,
  ) {
    val identicalToDevice: Boolean
      get() = kinds.all { it.new == 0 && it.changed == 0 && it.onlyOnDevice == 0 }
  }

  fun preview(current: RegimenBackupBundle, imported: RegimenBackupBundle): Preview {
    fun <T : Any> diff(
      label: String,
      device: List<T>,
      file: List<T>,
      id: (T) -> String,
      isLive: (T) -> Boolean,
    ): KindDiff {
      val deviceById = device.associateBy(id)
      val fileById = file.associateBy(id)
      return KindDiff(
        label = label,
        inFile = file.count(isLive),
        new = file.count { isLive(it) && deviceById[id(it)].let { d -> d == null || !isLive(d) } },
        changed = file.count { f -> deviceById[id(f)]?.let { d -> isLive(d) && d != f } == true },
        onlyOnDevice = device.count { isLive(it) && fileById[id(it)] == null },
      )
    }
    val liveLogs = imported.doseLogs.filter { it.deletedAtEpochMillis == null }
    return Preview(
      exportedAtEpochMillis = imported.exportedAtEpochMillis,
      sourceDeviceName = imported.devices.firstOrNull { it.id == imported.sourceDeviceId }?.name,
      version = imported.version,
      kinds = listOf(
        diff("Medications", current.medications, imported.medications, { it.id }, { it.deletedAtEpochMillis == null }),
        diff("Schedules", current.schedules, imported.schedules, { it.id }, { it.deletedAtEpochMillis == null }),
        diff("Dose history", current.doseLogs, imported.doseLogs, { it.id }, { it.deletedAtEpochMillis == null }),
        diff("Dose windows", current.buckets, imported.buckets, { it.id }, { true }),
        diff("Providers", current.providers, imported.providers, { it.id }, { it.deletedAtEpochMillis == null }),
        diff("Conditions", current.conditions, imported.conditions, { it.id }, { it.deletedAtEpochMillis == null }),
      ),
      firstDoseDateIso = liveLogs.minOfOrNull { it.scheduledDateIso },
      lastDoseDateIso = liveLogs.maxOfOrNull { it.scheduledDateIso },
    )
  }

  /**
   * Merging a file into this device: nothing is deleted, and where both have a record the more
   * recently edited version wins (a file has no "primary" to defer to). This device keeps its
   * own device settings, device list and primary claim.
   */
  fun mergeIntoDevice(
    current: RegimenBackupBundle,
    imported: RegimenBackupBundle,
    deviceId: String,
    nowMillis: Long = System.currentTimeMillis(),
  ): MergeResult {
    val result = BundleMerger.merge(
      base = null,
      local = current.copy(primary = null),
      remote = imported.copy(primary = null),
      localDeviceId = deviceId,
      nowMillis = nowMillis,
    )
    return result.copy(
      bundle = result.bundle.copy(primary = current.primary, devices = current.devices, settings = current.settings),
    )
  }
}
