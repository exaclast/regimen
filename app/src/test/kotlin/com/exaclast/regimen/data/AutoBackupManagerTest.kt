package com.exaclast.regimen.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.sync.BundleCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBackupManagerTest {

  private lateinit var app: Application
  private lateinit var repo: RegimenRepository
  private lateinit var backup: BackupManager
  private lateinit var autoBackup: AutoBackupManager

  @Before
  fun setUp() {
    RegimenRepository.resetForTests()
    AutoBackupManager.resetForTests()
    DoseNotificationManager.resetForTests()
    app = ApplicationProvider.getApplicationContext()
    repo = RegimenRepository.getInstance(app)
    backup = BackupManager(app, repo)
    autoBackup = AutoBackupManager.getInstance(app)
    autoBackup.prefs.update { AutoBackupStatus() }
    app.getSharedPreferences(BackupKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
  }

  @After
  fun tearDown() {
    AutoBackupManager.resetForTests()
    RegimenRepository.resetForTests()
    DoseNotificationManager.resetForTests()
  }

  @Test
  fun preferences_persistFrequencyKeepCountAndFolder() {
    val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")
    autoBackup.setFolder(uri)
    autoBackup.setFrequency(AutoBackupFrequency.WEEKLY)
    autoBackup.setKeepCount(20)

    val reloaded = AutoBackupPreferences(app).current()
    assertEquals(uri.toString(), reloaded.treeUri)
    assertEquals("Backups", reloaded.folderLabel)
    assertEquals(AutoBackupFrequency.WEEKLY, reloaded.frequency)
    assertEquals(20, reloaded.keepCount)
  }

  @Test
  fun runBackupNow_withoutFolder_marksNotConfiguredAndDisables() {
    autoBackup.prefs.update { AutoBackupStatus(enabled = true, treeUri = null) }
    val result = autoBackup.runBackupNow()

    assertEquals(AutoBackupManager.RunResult.PermanentFailure, result)
    assertFalse(autoBackup.status.value.enabled)
    assertEquals(AutoBackupError.NOT_CONFIGURED, autoBackup.status.value.lastError)
  }

  @Test
  fun runBackupNow_whenKeyLost_marksKeyLostWhileKeepingEnabledForResume() {
    val dataKey = BundleCrypto.newDataKey()
    val envelope = BundleCrypto.wrapWithPassphrase(dataKey, "correct-horse-battery".toCharArray())
    app.getSharedPreferences(BackupKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("envelope", BackupFiles.json.encodeToString(BundleCrypto.KeyEnvelope.serializer(), envelope))
      .putString("wrap_iv", "AAAAAAAAAAAAAAAA")
      .putString("wrapped_data_key", "AAAA")
      .commit()

    autoBackup.prefs.update {
      AutoBackupStatus(
        enabled = true,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
        folderLabel = "Backups",
      )
    }

    val result = autoBackup.runBackupNow()

    assertEquals(AutoBackupManager.RunResult.PermanentFailure, result)
    assertTrue(autoBackup.status.value.enabled)
    assertEquals(AutoBackupError.KEY_LOST, autoBackup.status.value.lastError)
  }

  @Test
  fun runScheduledBackup_whenDisabled_skipsWithoutError() {
    autoBackup.prefs.update { AutoBackupStatus(enabled = false) }
    assertEquals(AutoBackupManager.RunResult.Skipped, autoBackup.runScheduledBackup())
    assertEquals(AutoBackupError.NONE, autoBackup.status.value.lastError)
  }
}
