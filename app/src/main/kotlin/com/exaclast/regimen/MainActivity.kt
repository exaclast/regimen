package com.exaclast.regimen

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.exaclast.regimen.model.ClockPreferences

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(this)
    setContent {
      RegimenApp()
    }
  }

  override fun onResume() {
    super.onResume()
    // Pick up 12/24-hour changes made in system settings while the app was in the background.
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(this)
  }
}
