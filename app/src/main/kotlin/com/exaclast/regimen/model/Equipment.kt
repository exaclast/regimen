package com.exaclast.regimen.model

import kotlinx.serialization.Serializable

/**
 * A syringe kind with the sizes it usually comes in. [unitsPerMl] is set for insulin syringes, whose
 * markings are units; [needleAttached] means there's no separate draw needle.
 */
@Serializable
enum class SyringeType(
  val displayName: String,
  val volumesMl: List<Double>,
  val needleAttached: Boolean,
  val unitsPerMl: Int? = null,
) {
  INSULIN_U100("U-100 insulin", listOf(0.3, 0.5, 1.0), needleAttached = true, unitsPerMl = 100),
  INSULIN_U500("U-500 insulin", listOf(0.5), needleAttached = true, unitsPerMl = 500),
  TUBERCULIN("Tuberculin", listOf(1.0), needleAttached = false),
  LUER_LOCK("Luer-lock", listOf(1.0, 3.0, 5.0, 10.0), needleAttached = false),
  LUER_SLIP("Luer-slip", listOf(1.0, 3.0, 5.0, 10.0), needleAttached = false),
  OTHER("Other", emptyList(), needleAttached = false);

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
  data class HubColor(val name: String, val argb: Long)

  private val colors = mapOf(
    16 to HubColor("White", 0xFFF5F5F5),
    18 to HubColor("Pink", 0xFFF48FB1),
    19 to HubColor("Cream", 0xFFF3E5AB),
    20 to HubColor("Yellow", 0xFFFDD835),
    21 to HubColor("Green", 0xFF2E7D32),
    22 to HubColor("Black", 0xFF212121),
    23 to HubColor("Blue", 0xFF1565C0),
    24 to HubColor("Purple", 0xFF7E57C2),
    25 to HubColor("Orange", 0xFFFB8C00),
    26 to HubColor("Brown", 0xFF6D4C41),
    27 to HubColor("Grey", 0xFF9E9E9E),
    28 to HubColor("Blue-green", 0xFF00897B),
    29 to HubColor("Red", 0xFFE53935),
    30 to HubColor("Yellow", 0xFFFDD835),
  )

  fun colorFor(gauge: Int?): HubColor? = gauge?.let { colors[it] }
}
