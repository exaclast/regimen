package com.exaclast.regimen.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.SyringeType
import com.exaclast.regimen.model.TimeOfDayBucket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class RegimenDatabase(context: Context) :
  SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE medications (
        id TEXT PRIMARY KEY NOT NULL,
        formula_name TEXT NOT NULL,
        ndc11 TEXT,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE providers (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE indications (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE time_buckets (
        id TEXT PRIMARY KEY NOT NULL,
        sort_order INTEGER NOT NULL,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE schedules (
        id TEXT PRIMARY KEY NOT NULL,
        medication_id TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE dose_logs (
        id TEXT PRIMARY KEY NOT NULL,
        medication_id TEXT NOT NULL,
        scheduled_date_iso TEXT NOT NULL,
        action_timestamp INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX idx_schedules_med ON schedules(medication_id)")
    db.execSQL("CREATE INDEX idx_logs_med ON dose_logs(medication_id)")
    db.execSQL("CREATE INDEX idx_logs_date ON dose_logs(scheduled_date_iso)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Pre-release: only keeps development installs working across the prescriber → provider rename.
    if (oldVersion < 2) db.execSQL("ALTER TABLE prescribers RENAME TO providers")
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE conditions RENAME TO indications")
      migrateMedicationPayloads(db, ::migrateMedicationPayload)
    }
    if (oldVersion < 4) migrateMedicationPayloads(db, ::migrateSitePayload)
    if (oldVersion < 5) migrateMedicationPayloads(db, ::migrateSyringePayload)
  }

  /** Rewrites only the payload: `updated_at` stays, since a migration isn't an edit to sync. */
  private fun migrateMedicationPayloads(db: SQLiteDatabase, migrate: (String) -> String) {
    val rows = buildList {
      db.rawQuery("SELECT id, payload_json FROM medications", null).use { cursor ->
        while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
      }
    }
    rows.forEach { (id, payload) ->
      val migrated = migrate(payload)
      if (migrated != payload) {
        val values = ContentValues().apply { put("payload_json", migrated) }
        db.update("medications", values, "id = ?", arrayOf(id))
      }
    }
  }

  /** Runs [block] in one transaction: much faster for bulk writes, and all-or-nothing. */
  fun <T> inTransaction(block: () -> T): T {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val result = block()
      db.setTransactionSuccessful()
      return result
    } finally {
      db.endTransaction()
    }
  }

  /** Soft-deleted rows are tombstones: hidden from the app, but kept so deletions can sync. */
  private fun liveOnly(includeDeleted: Boolean) = if (includeDeleted) "" else "WHERE deleted_at IS NULL"

  fun getAllMedications(includeDeleted: Boolean = false): List<Medication> {
    val result = mutableListOf<Medication>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM medications ${liveOnly(includeDeleted)} ORDER BY formula_name COLLATE NOCASE ASC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<Medication>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertMedication(medication: Medication) {
    val values = ContentValues().apply {
      put("id", medication.id)
      put("formula_name", medication.formulaName)
      put("ndc11", medication.ndc11)
      put("updated_at", medication.updatedAtEpochMillis)
      put("deleted_at", medication.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(medication))
    }
    writableDatabase.insertWithOnConflict("medications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getAllProviders(includeDeleted: Boolean = false): List<Provider> {
    val result = mutableListOf<Provider>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM providers ${liveOnly(includeDeleted)} ORDER BY is_pcp_sort DESC, name COLLATE NOCASE ASC".let {
        "SELECT payload_json FROM providers ${liveOnly(includeDeleted)} ORDER BY name COLLATE NOCASE ASC"
      },
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<Provider>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result.sortedWith(
      compareByDescending<Provider> { it.isPcp }.thenByDescending { it.isRegular }.thenBy { it.name.lowercase() }
    )
  }

  fun upsertProvider(provider: Provider) {
    val values = ContentValues().apply {
      put("id", provider.id)
      put("name", provider.name)
      put("updated_at", provider.updatedAtEpochMillis)
      put("deleted_at", provider.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(provider))
    }
    writableDatabase.insertWithOnConflict("providers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getAllIndications(includeDeleted: Boolean = false): List<Indication> {
    val result = mutableListOf<Indication>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM indications ${liveOnly(includeDeleted)} ORDER BY name COLLATE NOCASE ASC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<Indication>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertIndication(indication: Indication) {
    val values = ContentValues().apply {
      put("id", indication.id)
      put("name", indication.name)
      put("updated_at", indication.updatedAtEpochMillis)
      put("deleted_at", indication.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(indication))
    }
    writableDatabase.insertWithOnConflict("indications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getAllBuckets(): List<TimeOfDayBucket> {
    val result = mutableListOf<TimeOfDayBucket>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM time_buckets ORDER BY sort_order ASC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<TimeOfDayBucket>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertBucket(bucket: TimeOfDayBucket) {
    val values = ContentValues().apply {
      put("id", bucket.id)
      put("sort_order", bucket.sortOrder)
      put("payload_json", json.encodeToString(bucket))
    }
    writableDatabase.insertWithOnConflict("time_buckets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun deleteBucket(bucketId: String) {
    writableDatabase.delete("time_buckets", "id = ?", arrayOf(bucketId))
  }

  fun getAllSchedules(includeDeleted: Boolean = false): List<MedicationSchedule> {
    val result = mutableListOf<MedicationSchedule>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM schedules ${liveOnly(includeDeleted)}",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<MedicationSchedule>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertSchedule(schedule: MedicationSchedule) {
    val values = ContentValues().apply {
      put("id", schedule.id)
      put("medication_id", schedule.medicationId)
      put("updated_at", schedule.updatedAtEpochMillis)
      put("deleted_at", schedule.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(schedule))
    }
    writableDatabase.insertWithOnConflict("schedules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getAllDoseLogs(includeDeleted: Boolean = false): List<DoseLog> {
    val result = mutableListOf<DoseLog>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM dose_logs ${liveOnly(includeDeleted)} ORDER BY action_timestamp DESC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<DoseLog>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertDoseLog(log: DoseLog) {
    val values = ContentValues().apply {
      put("id", log.id)
      put("medication_id", log.medicationId)
      put("scheduled_date_iso", log.scheduledDateIso)
      put("action_timestamp", log.actionTimestampMillis)
      put("updated_at", log.updatedAtEpochMillis)
      put("deleted_at", log.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(log))
    }
    writableDatabase.insertWithOnConflict("dose_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun clearUserRecords() {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("dose_logs", null, null)
      db.delete("schedules", null, null)
      db.delete("medications", null, null)
      db.delete("providers", null, null)
      db.delete("indications", null, null)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  companion object {
    private const val DB_NAME = "regimen_store.db"
    private const val DB_VERSION = 5

    /**
     * v5: the free-text syringe description becomes a [SyringeType]. A recognised type drops the text, which
     * only repeated the type and volume; an unrecognised one keeps it as an "Other" description.
     */
    internal fun migrateSyringePayload(payload: String): String {
      val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return payload
      val details = root["injectableDetails"] as? JsonObject ?: return payload
      if ("syringeType" in details) return payload
      val description = (details["syringeDescription"] as? JsonPrimitive)?.contentOrNull ?: return payload
      val type = SyringeType.infer(description) ?: return payload
      val migrated = details.toMutableMap().apply {
        put("syringeType", JsonPrimitive(type.name))
        if (type != SyringeType.OTHER) remove("syringeDescription")
      }
      return JsonObject(root.toMutableMap().apply { put("injectableDetails", JsonObject(migrated)) }).toString()
    }

    private const val LEGACY_ROTATION_FLAG = "siteRotationEnabled"
    private const val LEGACY_ROTATION_SITES = "rotationSites"

    /**
     * v4: the rotation switch became a site mode, and `rotationSites` became `sites`. Rotation on becomes
     * ROTATE; off becomes OFF, keeping any sites. Returns [payload] untouched if it has neither old key.
     */
    internal fun migrateSitePayload(payload: String): String {
      val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return payload
      if (LEGACY_ROTATION_FLAG !in root && LEGACY_ROTATION_SITES !in root) return payload
      val rotating = (root[LEGACY_ROTATION_FLAG] as? JsonPrimitive)?.booleanOrNull == true
      val migrated = root.toMutableMap().apply {
        remove(LEGACY_ROTATION_FLAG)
        remove(LEGACY_ROTATION_SITES)?.let { put("sites", it) }
        put("siteMode", JsonPrimitive(if (rotating) "ROTATE" else "OFF"))
      }
      return JsonObject(migrated).toString()
    }

    private const val LEGACY_SINGLE_LINK = "indicationConditionId"
    private const val LEGACY_LINK_LIST = "indicationConditionIds"

    /**
     * Moves a stored medication's link(s) from either older key to `indicationIds`. Returns [payload]
     * untouched if it has neither key or isn't readable.
     */
    internal fun migrateMedicationPayload(payload: String): String {
      val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return payload
      if (LEGACY_SINGLE_LINK !in root && LEGACY_LINK_LIST !in root) return payload
      val ids = buildList {
        (root[LEGACY_LINK_LIST] as? JsonArray)?.forEach { element ->
          (element as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
        (root[LEGACY_SINGLE_LINK] as? JsonPrimitive)?.contentOrNull?.let(::add)
      }.distinct()
      val migrated = root.toMutableMap().apply {
        remove(LEGACY_SINGLE_LINK)
        remove(LEGACY_LINK_LIST)
        put("indicationIds", JsonArray(ids.map { JsonPrimitive(it) }))
      }
      return JsonObject(migrated).toString()
    }
  }
}

