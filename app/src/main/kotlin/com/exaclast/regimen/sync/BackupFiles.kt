package com.exaclast.regimen.sync

import androidx.annotation.StringRes
import com.exaclast.regimen.R
import com.exaclast.regimen.model.Message
import com.exaclast.regimen.model.MessageException
import com.exaclast.regimen.model.RegimenBackupBundle
import com.exaclast.regimen.model.TimeOfDayBucket
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Reading, previewing and merging backup files. Pure logic; storage and keys live in the data layer. */
object BackupFiles {

  class InvalidBackupException(val reason: String) : MessageException(Message(R.string.backup_err_invalid)) {
    override val message: String
      get() = reason
  }

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
    val text = String(plaintext, Charsets.UTF_8)
    val element = try {
      json.parseToJsonElement(text)
    } catch (e: Exception) {
      throw BundleCrypto.NotABackupException()
    }
    val obj = element as? JsonObject ?: throw BundleCrypto.NotABackupException()
    if (obj["format"]?.jsonPrimitive?.contentOrNull != RegimenBackupBundle.FORMAT) {
      throw BundleCrypto.NotABackupException()
    }
    val bundle = try {
      json.decodeFromJsonElement(RegimenBackupBundle.serializer(), obj)
    } catch (e: Exception) {
      throw BundleCrypto.CorruptedException()
    }
    if (bundle.version > RegimenBackupBundle.CURRENT_VERSION) {
      throw BundleCrypto.UnsupportedFormatException(bundle.version)
    }
    validate(bundle)
    return bundle
  }

  /**
   * Validates integrity, field bounds, ISO dates, and relational references across a backup bundle.
   * Throws [InvalidBackupException] on any violation.
   */
  fun validate(bundle: RegimenBackupBundle) {
    if (bundle.format != RegimenBackupBundle.FORMAT) {
      throw InvalidBackupException("Format marker must be '${RegimenBackupBundle.FORMAT}', got '${bundle.format}'")
    }
    if (bundle.version < 1) {
      throw InvalidBackupException("Version must be at least 1, got ${bundle.version}")
    }
    if (bundle.version > RegimenBackupBundle.CURRENT_VERSION) {
      throw BundleCrypto.UnsupportedFormatException(bundle.version)
    }

    // Time buckets
    val bucketIds = mutableSetOf<String>()
    for (bucket in bundle.buckets) {
      if (bucket.id.isBlank()) throw InvalidBackupException("Bucket ID cannot be blank")
      if (!bucketIds.add(bucket.id)) throw InvalidBackupException("Duplicate bucket ID: ${bucket.id}")
      if (bucket.name.isBlank()) throw InvalidBackupException("Bucket name cannot be blank: ${bucket.id}")
      if (bucket.hour !in 0..23) throw InvalidBackupException("Bucket ${bucket.id} has invalid hour: ${bucket.hour}")
      if (bucket.minute !in 0..59) throw InvalidBackupException("Bucket ${bucket.id} has invalid minute: ${bucket.minute}")
      if (bucket.endHour !in 0..23) throw InvalidBackupException("Bucket ${bucket.id} has invalid end hour: ${bucket.endHour}")
      if (bucket.endMinute !in 0..59) throw InvalidBackupException("Bucket ${bucket.id} has invalid end minute: ${bucket.endMinute}")
      if (bucket.hour * 60 + bucket.minute == bucket.endHour * 60 + bucket.endMinute) {
        throw InvalidBackupException("Bucket ${bucket.id} has zero duration")
      }
    }
    val availableBuckets = if (bundle.buckets.isEmpty()) {
      TimeOfDayBucket.defaultBuckets().mapTo(HashSet()) { it.id }
    } else {
      bucketIds
    }

    // Providers
    val providerIds = mutableSetOf<String>()
    for (provider in bundle.providers) {
      if (provider.id.isBlank()) throw InvalidBackupException("Provider ID cannot be blank")
      if (!providerIds.add(provider.id)) throw InvalidBackupException("Duplicate provider ID: ${provider.id}")
      if (provider.name.isBlank()) throw InvalidBackupException("Provider name cannot be blank: ${provider.id}")
      val start = provider.careStartDateIso?.let { checkIsoDate("Provider ${provider.id} careStartDateIso", it) }
      val end = provider.careEndDateIso?.let { checkIsoDate("Provider ${provider.id} careEndDateIso", it) }
      if (start != null && end != null && start.isAfter(end)) {
        throw InvalidBackupException("Provider ${provider.id} care start date $start is after end date $end")
      }
    }

    // Indications
    val indicationIds = mutableSetOf<String>()
    for (indication in bundle.indications) {
      if (indication.id.isBlank()) throw InvalidBackupException("Indication ID cannot be blank")
      if (!indicationIds.add(indication.id)) throw InvalidBackupException("Duplicate indication ID: ${indication.id}")
      if (indication.name.isBlank()) throw InvalidBackupException("Indication name cannot be blank: ${indication.id}")
      val diag = indication.diagnosedDateIso?.let { checkIsoDate("Indication ${indication.id} diagnosedDateIso", it) }
      val end = indication.endDateIso?.let { checkIsoDate("Indication ${indication.id} endDateIso", it) }
      if (diag != null && end != null && diag.isAfter(end)) {
        throw InvalidBackupException("Indication ${indication.id} diagnosed date $diag is after end date $end")
      }
      for (providerId in indication.providerIds) {
        if (providerId !in providerIds) {
          throw InvalidBackupException("Indication ${indication.id} references missing provider $providerId")
        }
      }
      for (primaryId in indication.primaryIds) {
        if (primaryId == indication.id) {
          throw InvalidBackupException("Indication ${indication.id} cannot be secondary to itself")
        }
        if (bundle.indications.none { it.id == primaryId }) {
          throw InvalidBackupException("Indication ${indication.id} references missing primary indication $primaryId")
        }
      }
    }

    // Medications & Supplements
    val medicationIds = mutableSetOf<String>()
    for (med in bundle.medications) {
      if (med.id.isBlank()) throw InvalidBackupException("Medication ID cannot be blank")
      if (!medicationIds.add(med.id)) throw InvalidBackupException("Duplicate medication ID: ${med.id}")
      if (med.formulaName.isBlank()) throw InvalidBackupException("Medication formula name cannot be blank: ${med.id}")
      if (!med.defaultDoseAmount.isFinite() || med.defaultDoseAmount <= 0.0) {
        throw InvalidBackupException("Medication ${med.id} has invalid default dose amount: ${med.defaultDoseAmount}")
      }
      if (med.prescriberId != null && med.prescriberId !in providerIds) {
        throw InvalidBackupException("Medication ${med.id} references missing prescriber ${med.prescriberId}")
      }
      for (indId in med.indicationIds) {
        if (indId !in indicationIds) {
          throw InvalidBackupException("Medication ${med.id} references missing indication $indId")
        }
      }
      med.prescribedDateIso?.let { checkIsoDate("Medication ${med.id} prescribedDateIso", it) }
      for (adj in med.adjustments) {
        checkIsoDate("Adjustment on medication ${med.id} effectiveDateIso", adj.effectiveDateIso)
        adj.defaultDose?.let { dose ->
          if (!dose.amount.isFinite() || dose.amount <= 0.0) {
            throw InvalidBackupException("Adjustment on medication ${med.id} has invalid default dose amount: ${dose.amount}")
          }
        }
        for (schedDose in adj.scheduleDoses) {
          if (!schedDose.amount.isFinite() || schedDose.amount <= 0.0) {
            throw InvalidBackupException("Adjustment on medication ${med.id} has invalid schedule dose: ${schedDose.amount}")
          }
        }
      }
    }
    for (supp in bundle.supplements) {
      if (supp.id.isBlank()) throw InvalidBackupException("Supplement ID cannot be blank")
      if (!medicationIds.add(supp.id)) throw InvalidBackupException("Duplicate supplement ID: ${supp.id}")
      if (supp.name.isBlank()) throw InvalidBackupException("Supplement name cannot be blank: ${supp.id}")
      if (!supp.defaultDoseAmount.isFinite() || supp.defaultDoseAmount <= 0.0) {
        throw InvalidBackupException("Supplement ${supp.id} has invalid default dose amount: ${supp.defaultDoseAmount}")
      }
      if (supp.prescriberId != null && supp.prescriberId !in providerIds) {
        throw InvalidBackupException("Supplement ${supp.id} references missing prescriber ${supp.prescriberId}")
      }
      for (indId in supp.indicationIds) {
        if (indId !in indicationIds) {
          throw InvalidBackupException("Supplement ${supp.id} references missing indication $indId")
        }
      }
      supp.startedDateIso?.let { checkIsoDate("Supplement ${supp.id} startedDateIso", it) }
      for (adj in supp.adjustments) {
        checkIsoDate("Adjustment on supplement ${supp.id} effectiveDateIso", adj.effectiveDateIso)
        adj.defaultDose?.let { dose ->
          if (!dose.amount.isFinite() || dose.amount <= 0.0) {
            throw InvalidBackupException("Adjustment on supplement ${supp.id} has invalid default dose amount: ${dose.amount}")
          }
        }
        for (schedDose in adj.scheduleDoses) {
          if (!schedDose.amount.isFinite() || schedDose.amount <= 0.0) {
            throw InvalidBackupException("Adjustment on supplement ${supp.id} has invalid schedule dose: ${schedDose.amount}")
          }
        }
      }
    }

    // Schedules
    val scheduleIds = mutableSetOf<String>()
    for (sched in bundle.schedules) {
      if (sched.id.isBlank()) throw InvalidBackupException("Schedule ID cannot be blank")
      if (!scheduleIds.add(sched.id)) throw InvalidBackupException("Duplicate schedule ID: ${sched.id}")
      if (sched.medicationId !in medicationIds) {
        throw InvalidBackupException("Schedule ${sched.id} references missing medication ${sched.medicationId}")
      }
      if (!sched.doseAmount.isFinite() || sched.doseAmount <= 0.0) {
        throw InvalidBackupException("Schedule ${sched.id} has invalid dose amount: ${sched.doseAmount}")
      }
      if (sched.frequencyType != com.exaclast.regimen.model.ScheduleFrequencyType.AS_NEEDED && sched.bucketIds.isEmpty()) {
        throw InvalidBackupException("Schedule ${sched.id} has no dose windows")
      }
      for (bId in sched.bucketIds) {
        if (bId !in availableBuckets) {
          throw InvalidBackupException("Schedule ${sched.id} references missing bucket $bId")
        }
      }
      if (sched.startBucketId != null && sched.startBucketId !in availableBuckets) {
        throw InvalidBackupException("Schedule ${sched.id} references missing start bucket ${sched.startBucketId}")
      }
      if (sched.endBucketId != null && sched.endBucketId !in availableBuckets) {
        throw InvalidBackupException("Schedule ${sched.id} references missing end bucket ${sched.endBucketId}")
      }
      val start = sched.startDateIso?.let { checkIsoDate("Schedule ${sched.id} startDateIso", it) }
      val end = sched.endDateIso?.let { checkIsoDate("Schedule ${sched.id} endDateIso", it) }
      if (start != null && end != null && start.isAfter(end)) {
        throw InvalidBackupException("Schedule ${sched.id} start date $start is after end date $end")
      }
      for (specDate in sched.specificDatesIso) {
        checkIsoDate("Schedule ${sched.id} specific date", specDate)
      }
      if (sched.intervalDays < 1) {
        throw InvalidBackupException("Schedule ${sched.id} has invalid interval days: ${sched.intervalDays}")
      }
    }

    // Dose logs
    val logIds = mutableSetOf<String>()
    for (log in bundle.doseLogs) {
      if (log.id.isBlank()) throw InvalidBackupException("Dose log ID cannot be blank")
      if (!logIds.add(log.id)) throw InvalidBackupException("Duplicate dose log ID: ${log.id}")
      if (log.medicationId !in medicationIds) {
        throw InvalidBackupException("Dose log ${log.id} references missing medication ${log.medicationId}")
      }
      if (log.scheduleId != null && log.scheduleId !in scheduleIds) {
        throw InvalidBackupException("Dose log ${log.id} references missing schedule ${log.scheduleId}")
      }
      if (log.bucketId != null && log.bucketId !in availableBuckets) {
        throw InvalidBackupException("Dose log ${log.id} references missing bucket ${log.bucketId}")
      }
      checkIsoDate("Dose log ${log.id} scheduledDateIso", log.scheduledDateIso)
      if (!log.amountTaken.isFinite() || log.amountTaken < 0.0) {
        throw InvalidBackupException("Dose log ${log.id} has invalid amount taken: ${log.amountTaken}")
      }
    }
  }

  private fun checkIsoDate(context: String, dateIso: String): LocalDate =
    try {
      LocalDate.parse(dateIso)
    } catch (e: Exception) {
      throw InvalidBackupException("$context is not a valid ISO date: $dateIso")
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
    @param:StringRes val labelRes: Int,
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
      @StringRes labelRes: Int,
      device: List<T>,
      file: List<T>,
      id: (T) -> String,
      isLive: (T) -> Boolean,
    ): KindDiff {
      val deviceById = device.associateBy(id)
      val fileById = file.associateBy(id)
      return KindDiff(
        labelRes = labelRes,
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
        diff(R.string.backup_kind_medications, current.medications, imported.medications, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_supplements, current.supplements, imported.supplements, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_schedules, current.schedules, imported.schedules, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_dose_history, current.doseLogs, imported.doseLogs, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_dose_windows, current.buckets, imported.buckets, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_providers, current.providers, imported.providers, { it.id }, { it.deletedAtEpochMillis == null }),
        diff(R.string.backup_kind_indications, current.indications, imported.indications, { it.id }, { it.deletedAtEpochMillis == null }),
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
