package com.exaclast.regimen.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.TimeOfDayBucket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
      CREATE TABLE prescribers (
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
      CREATE TABLE conditions (
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
    // Future schema migrations go here
  }

  fun getAllMedications(): List<Medication> {
    val result = mutableListOf<Medication>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM medications WHERE deleted_at IS NULL ORDER BY formula_name COLLATE NOCASE ASC",
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

  fun getAllPrescribers(): List<Prescriber> {
    val result = mutableListOf<Prescriber>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM prescribers WHERE deleted_at IS NULL ORDER BY is_pcp_sort DESC, name COLLATE NOCASE ASC".let {
        "SELECT payload_json FROM prescribers WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE ASC"
      },
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<Prescriber>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result.sortedWith(compareByDescending<Prescriber> { it.isPcp }.thenBy { it.name.lowercase() })
  }

  fun upsertPrescriber(prescriber: Prescriber) {
    val values = ContentValues().apply {
      put("id", prescriber.id)
      put("name", prescriber.name)
      put("updated_at", prescriber.updatedAtEpochMillis)
      put("deleted_at", prescriber.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(prescriber))
    }
    writableDatabase.insertWithOnConflict("prescribers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getAllConditions(): List<Condition> {
    val result = mutableListOf<Condition>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM conditions WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE ASC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        runCatching {
          json.decodeFromString<Condition>(cursor.getString(0))
        }.getOrNull()?.let { result.add(it) }
      }
    }
    return result
  }

  fun upsertCondition(condition: Condition) {
    val values = ContentValues().apply {
      put("id", condition.id)
      put("name", condition.name)
      put("updated_at", condition.updatedAtEpochMillis)
      put("deleted_at", condition.deletedAtEpochMillis)
      put("payload_json", json.encodeToString(condition))
    }
    writableDatabase.insertWithOnConflict("conditions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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

  fun getAllSchedules(): List<MedicationSchedule> {
    val result = mutableListOf<MedicationSchedule>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM schedules WHERE deleted_at IS NULL",
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

  fun getAllDoseLogs(): List<DoseLog> {
    val result = mutableListOf<DoseLog>()
    readableDatabase.rawQuery(
      "SELECT payload_json FROM dose_logs WHERE deleted_at IS NULL ORDER BY action_timestamp DESC",
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
      db.delete("prescribers", null, null)
      db.delete("conditions", null, null)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  companion object {
    private const val DB_NAME = "regimen_store.db"
    private const val DB_VERSION = 1
  }
}

