package com.exaclast.regimen.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.exaclast.regimen.R
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

  /** Plans or edits a dated change (titration step, taper); a null [adjustmentId] plans a new one. */
  @Serializable data class AdjustmentEditor(val medicationId: String, val adjustmentId: String? = null) : Route

  @Serializable data class ProviderDetail(val id: String) : Route

  @Serializable data class ProviderEditor(val id: String? = null) : Route

  @Serializable data class IndicationDetail(val id: String) : Route

  /** [copyFromId]: start a new episode pre-filled from that past indication. */
  @Serializable data class IndicationEditor(val id: String? = null, val copyFromId: String? = null) : Route

  /** Corrects a logged dose (time, amount, site, note). */
  @Serializable data class DoseLogEditor(val id: String) : Route

  @Serializable data object Settings : Route

  /** Allergies, indications, providers and the past week's medications, shareable as text. */
  @Serializable data object CareSummary : Route

  @Serializable data class DoseWindowEditor(val id: String? = null) : Route

  /** Backup passphrase: set up, change, reset, or reveal the recovery code. */
  @Serializable data class BackupEncryption(val mode: BackupEncryptionMode) : Route

  /** Unlock, preview and apply a backup file picked in Settings. */
  @Serializable data object BackupRestore : Route
}

/** Bottom-bar destinations. */
enum class TopLevel(val route: Route, @param:StringRes val labelRes: Int, val icon: ImageVector) {
  TODAY(Route.Today, R.string.today_title, Icons.Default.CheckCircle),
  MEDICATIONS(Route.Medications, R.string.medications_title, Icons.Default.Medication),
  PROVIDERS(Route.Providers, R.string.providers_title, Icons.Default.Badge),
  INDICATIONS(Route.Indications, R.string.indications_title, Icons.Default.Favorite),
}

/** Full-screen task destinations hide the navigation bar. */
val Route.isFullScreenTask: Boolean
  get() = this is Route.MedicationEditor ||
    this is Route.ScheduleEditor ||
    this is Route.AdjustmentEditor ||
    this is Route.ProviderEditor ||
    this is Route.IndicationEditor ||
    this is Route.DoseLogEditor ||
    this is Route.Settings ||
    this is Route.CareSummary ||
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

  /**
   * Goes back only if [route] is still on top. A screen whose record was just deleted leaves itself this
   * way: while it animates out it's still composed, and an unconditional back would pop the screen
   * under it too (going back twice).
   */
  fun backFrom(route: Route) {
    if (current == route) back()
  }
}

@Serializable
enum class BackupEncryptionMode { SETUP, CHANGE, SHOW_CODE, RESET }
