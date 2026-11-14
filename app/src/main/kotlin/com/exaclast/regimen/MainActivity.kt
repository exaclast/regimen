package com.exaclast.regimen

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.exaclast.regimen.model.ClockPreferences

class MainActivity : ComponentActivity() {
  /** Bumped each time the launcher shortcut asks for the health summary. */
  private var openSummaryRequest by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(this)
    // Label photos show names; any left by an earlier run (killed mid-scan) go on a fresh launch.
    if (savedInstanceState == null) {
      com.exaclast.regimen.data.DrugBarcodeScanner.clearLabelPhotos(applicationContext)
      handleShortcut(intent)
    }
    publishShortcuts()
    setContent {
      RegimenApp(openSummaryRequest = openSummaryRequest)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShortcut(intent)
  }

  override fun onResume() {
    super.onResume()
    // Pick up 12/24-hour changes made in system settings while the app was in the background.
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(this)
  }

  private fun handleShortcut(intent: Intent?) {
    if (intent?.action == ACTION_OPEN_SUMMARY) openSummaryRequest++
  }

  /** The long-press launcher menu: straight to the health summary. */
  private fun publishShortcuts() {
    val summary = ShortcutInfoCompat.Builder(this, SHORTCUT_SUMMARY)
      .setShortLabel(getString(R.string.shortcut_summary_short))
      .setLongLabel(getString(R.string.shortcut_summary_long))
      .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_summary))
      .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_SUMMARY))
      .build()
    ShortcutManagerCompat.setDynamicShortcuts(this, listOf(summary))
  }

  private companion object {
    const val ACTION_OPEN_SUMMARY = "com.exaclast.regimen.action.OPEN_SUMMARY"
    const val SHORTCUT_SUMMARY = "health_summary"
  }
}
