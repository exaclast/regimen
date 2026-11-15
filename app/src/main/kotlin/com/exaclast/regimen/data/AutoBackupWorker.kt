package com.exaclast.regimen.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that writes an encrypted `.regimen` backup to the user's selected SAF folder
 * and prunes older managed backups according to the configured retention policy.
 */
class AutoBackupWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    when (AutoBackupManager.getInstance(applicationContext).runScheduledBackup()) {
      AutoBackupManager.RunResult.Success,
      AutoBackupManager.RunResult.Skipped -> Result.success()
      AutoBackupManager.RunResult.RetryableFailure ->
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
      AutoBackupManager.RunResult.PermanentFailure -> Result.failure()
    }
  }

  private companion object {
    const val MAX_RETRIES = 3
  }
}
