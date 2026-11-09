package com.exaclast.regimen.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Every navigable destination. Keys are serializable so the back stack survives process death. */
@Serializable
sealed interface Route : NavKey {
  @Serializable data object Today : Route

  @Serializable data object Medications : Route

  @Serializable data object Providers : Route

  @Serializable data object Indications : Route

  @Serializable data class MedicationDetail(val id: String, val promptForSchedule: Boolean = false) : Route

  @Serializable data class MedicationEditor(val id: String? = null) : Route

  @Serializable data class ScheduleEditor(val medicationId: String, val scheduleId: String? = null) : Route

  @Serializable data class ProviderDetail(val id: String) : Route

  @Serializable data class ProviderEditor(val id: String? = null) : Route

  @Serializable data class IndicationDetail(val id: String) : Route

  /** [copyFromId]: start a new episode pre-filled from that past indication. */
  @Serializable data class IndicationEditor(val id: String? = null, val copyFromId: String? = null) : Route

  @Serializable data object Settings : Route

  @Serializable data class DoseWindowEditor(val id: String? = null) : Route

  /** Backup passphrase: set up, change, reset, or reveal the recovery code. */
  @Serializable data class BackupEncryption(val mode: BackupEncryptionMode) : Route

  /** Unlock, preview and apply a backup file picked in Settings. */
  @Serializable data object BackupRestore : Route
}

/** Bottom-bar destinations. */
enum class TopLevel(val route: Route, val label: String, val icon: ImageVector) {
  TODAY(Route.Today, "Today", Icons.Default.CheckCircle),
  MEDICATIONS(Route.Medications, "Medications", Icons.Default.Medication),
  PROVIDERS(Route.Providers, "Providers", Icons.Default.Badge),
  INDICATIONS(Route.Indications, "Indications", Icons.Default.Favorite),
}

/** Full-screen task destinations hide the navigation bar. */
val Route.isFullScreenTask: Boolean
  get() = this is Route.MedicationEditor ||
    this is Route.ScheduleEditor ||
    this is Route.ProviderEditor ||
    this is Route.IndicationEditor ||
    this is Route.Settings ||
    this is Route.DoseWindowEditor ||
    this is Route.BackupEncryption ||
    this is Route.BackupRestore

/**
 * Thin wrapper over the Navigation 3 back stack.
 *
 * Stack shape: `[Today]` for the Today tab, `[Today, <Tab>, …]` for the other tabs, so system back from any
 * secondary tab returns to Today before leaving the app.
 */
class Navigator(private val backStack: NavBackStack<NavKey>) {

  val current: Route?
    get() = backStack.lastOrNull() as? Route

  /** The bottom-bar tab that owns the current screen. */
  val currentTab: TopLevel
    get() {
      val lastTop = backStack.lastOrNull { key -> TopLevel.entries.any { it.route == key } }
      return TopLevel.entries.firstOrNull { it.route == lastTop } ?: TopLevel.TODAY
    }

  fun selectTab(tab: TopLevel) {
    backStack.clear()
    backStack.add(Route.Today)
    if (tab != TopLevel.TODAY) backStack.add(tab.route)
  }

  fun navigate(route: Route) {
    backStack.add(route)
  }

  /** Replaces the current screen (e.g. an editor that hands off to the record it created). */
  fun replace(route: Route) {
    if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
    backStack.add(route)
  }

  fun back() {
    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
  }
}

@Serializable
enum class BackupEncryptionMode { SETUP, CHANGE, SHOW_CODE, RESET }
