package com.exaclast.regimen.sync

import android.content.Context
import android.os.Build
import com.exaclast.regimen.model.SyncDevice
import java.util.UUID

/**
 * This install's identity for sync. Kept in its own preferences file so it can be excluded from
 * Android's device backup: a phone restored from backup must get a new id, not clone the old one.
 */
class DeviceIdentity(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  val deviceId: String
    @Synchronized get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
      prefs.edit().putString(KEY_DEVICE_ID, it).putLong(KEY_FIRST_SEEN, System.currentTimeMillis()).apply()
    }

  val model: String
    get() = listOf(Build.MANUFACTURER, Build.MODEL)
      .filter { !it.isNullOrBlank() }
      .distinct()
      .joinToString(" ")
      .let { m -> if (Build.MODEL?.startsWith(Build.MANUFACTURER ?: "", ignoreCase = true) == true) Build.MODEL else m }

  /** User-chosen name, defaulting to the model ("Google Pixel 8a"). */
  var deviceName: String
    get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: model
    set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

  fun asSyncDevice(lastSyncedEpochMillis: Long = 0L): SyncDevice {
    val id = deviceId
    return SyncDevice(
      id = id,
      name = deviceName,
      model = model,
      firstSeenEpochMillis = prefs.getLong(KEY_FIRST_SEEN, System.currentTimeMillis()),
      lastSyncedEpochMillis = lastSyncedEpochMillis,
    )
  }

  companion object {
    const val PREFS_NAME = "regimen_device"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_FIRST_SEEN = "first_seen"
  }
}
