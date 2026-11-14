package com.exaclast.regimen.model

import androidx.annotation.StringRes
import com.exaclast.regimen.R
import kotlinx.serialization.Serializable

/**
 * A syringe kind with the sizes it usually comes in. [unitsPerMl] is set for insulin syringes, whose
 * markings are units; [needleAttached] means there's no separate draw needle.
 */
@Serializable
enum class SyringeType(
  override val labelRes: Int,
  val volumesMl: List<Double>,
  val needleAttached: Boolean,
  val unitsPerMl: Int? = null,
) : Labeled {
  INSULIN_U100(R.string.enum_syringe_u100, listOf(0.3, 0.5, 1.0), needleAttached = true, unitsPerMl = 100),
  INSULIN_U500(R.string.enum_syringe_u500, listOf(0.5), needleAttached = true, unitsPerMl = 500),
  TUBERCULIN(R.string.enum_syringe_tuberculin, listOf(1.0), needleAttached = false),
  LUER_LOCK(R.string.enum_syringe_luer_lock, listOf(1.0, 3.0, 5.0, 10.0), needleAttached = false),
  LUER_SLIP(R.string.enum_syringe_luer_slip, listOf(1.0, 3.0, 5.0, 10.0), needleAttached = false),
  OTHER(R.string.enum_syringe_other, emptyList(), needleAttached = false);

  companion object {
    /** Best guess from free text such as "0.5 mL U-100 Insulin Syringe"; [OTHER] when nothing matches. */
    fun infer(description: String?): SyringeType? {
      val text = description?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
      return when {
        "u-500" in text || "u500" in text -> INSULIN_U500
        "insulin" in text || "u-100" in text || "u100" in text -> INSULIN_U100
        "tuberculin" in text || Regex("""\btb\b""").containsMatchIn(text) -> TUBERCULIN
        "slip" in text -> LUER_SLIP
        "luer" in text || "lok" in text || "lock" in text -> LUER_LOCK
        else -> OTHER
      }
    }
  }
}

/**
 * Needle hub colors by gauge, from ISO 6009. Most brands follow it, but not all (some 25G hubs are blue),
 * so the app labels these as the standard color, not a guarantee.
 */
object NeedleHub {
  data class HubColor(@param:StringRes val nameRes: Int, val argb: Long)

  private val colors = mapOf(
    16 to HubColor(R.string.hub_white, 0xFFF5F5F5),
    18 to HubColor(R.string.hub_pink, 0xFFF48FB1),
    19 to HubColor(R.string.hub_cream, 0xFFF3E5AB),
    20 to HubColor(R.string.hub_yellow, 0xFFFDD835),
    21 to HubColor(R.string.hub_green, 0xFF2E7D32),
    22 to HubColor(R.string.hub_black, 0xFF212121),
    23 to HubColor(R.string.hub_blue, 0xFF1565C0),
    24 to HubColor(R.string.hub_purple, 0xFF7E57C2),
    25 to HubColor(R.string.hub_orange, 0xFFFB8C00),
    26 to HubColor(R.string.hub_brown, 0xFF6D4C41),
    27 to HubColor(R.string.hub_grey, 0xFF9E9E9E),
    28 to HubColor(R.string.hub_blue_green, 0xFF00897B),
    29 to HubColor(R.string.hub_red, 0xFFE53935),
    30 to HubColor(R.string.hub_yellow, 0xFFFDD835),
  )

  fun colorFor(gauge: Int?): HubColor? = gauge?.let { colors[it] }
}
