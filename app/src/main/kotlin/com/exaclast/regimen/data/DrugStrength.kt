package com.exaclast.regimen.data

import kotlin.math.abs
import kotlin.math.max

/**
 * One active-ingredient strength, normalized to canonical units.
 *
 * @property value amount of [unit] per one [per] (or per dose unit when [per] is null).
 * @property unit canonical numerator unit: `mg`, `ml`, `unit`, `meq`, `mmol`, or `%`.
 * @property per canonical denominator unit (`ml`, `g`, `d`, `actuation`), or null for "per dose unit".
 */
internal data class StrengthComponent(
  val value: Double,
  val unit: String,
  val per: String?,
)

/**
 * Parses and compares drug strengths numerically.
 *
 * Strength comparison must NEVER use substring matching: `"2.5mg".contains("5mg")` and
 * `"0.5mg".contains("5mg")` are both true, which turns a 5 mg label into a 0.5 / 2.5 / 25 mg match.
 */
internal object DrugStrength {

  /** Relative tolerance that absorbs label rounding (e.g. "2 mg/1.5 mL" vs "1.34 mg/mL"). */
  private const val RELATIVE_TOLERANCE = 0.01

  private data class UnitSpec(val canonical: String, val factor: Double)

  private val NUMERATOR_UNITS = mapOf(
    "g" to UnitSpec("mg", 1000.0),
    "gm" to UnitSpec("mg", 1000.0),
    "mg" to UnitSpec("mg", 1.0),
    "mcg" to UnitSpec("mg", 0.001),
    "ug" to UnitSpec("mg", 0.001),
    "ng" to UnitSpec("mg", 0.000001),
    "ml" to UnitSpec("ml", 1.0),
    "l" to UnitSpec("ml", 1000.0),
    "unit" to UnitSpec("unit", 1.0),
    "units" to UnitSpec("unit", 1.0),
    "u" to UnitSpec("unit", 1.0),
    "iu" to UnitSpec("unit", 1.0),
    "[iu]" to UnitSpec("unit", 1.0),
    "[usp'u]" to UnitSpec("unit", 1.0),
    "meq" to UnitSpec("meq", 1.0),
    "[meq]" to UnitSpec("meq", 1.0),
    "mmol" to UnitSpec("mmol", 1.0),
    "%" to UnitSpec("%", 1.0),
  )

  private val DENOMINATOR_UNITS = mapOf(
    "ml" to UnitSpec("ml", 1.0),
    "l" to UnitSpec("ml", 1000.0),
    "g" to UnitSpec("g", 1.0),
    "h" to UnitSpec("d", 1.0 / 24.0),
    "hr" to UnitSpec("d", 1.0 / 24.0),
    "hour" to UnitSpec("d", 1.0 / 24.0),
    "hours" to UnitSpec("d", 1.0 / 24.0),
    "d" to UnitSpec("d", 1.0),
    "day" to UnitSpec("d", 1.0),
    "actuat" to UnitSpec("actuation", 1.0),
    "actuation" to UnitSpec("actuation", 1.0),
    "actuations" to UnitSpec("actuation", 1.0),
    "spray" to UnitSpec("actuation", 1.0),
    "sprays" to UnitSpec("actuation", 1.0),
    "puff" to UnitSpec("actuation", 1.0),
    "puffs" to UnitSpec("actuation", 1.0),
  )

  private const val NUM = """(\d+(?:\.\d+)?|\.\d+)"""

  // Longest alternatives first so "mcg" is not read as "mg", "ml" not as "l", etc.
  private val NUM_UNIT = NUMERATOR_UNITS.keys
    .sortedByDescending { it.length }
    .joinToString("|") { Regex.escape(it) }
  private val DEN_UNIT = DENOMINATOR_UNITS.keys
    .sortedByDescending { it.length }
    .joinToString("|") { Regex.escape(it) }

  private val COMPONENT_REGEX = Regex(
    "$NUM\\s*($NUM_UNIT)(?![a-z])" +
      "(?:" +
      "\\s*/\\s*(?:$NUM\\s*)?($DEN_UNIT)(?![a-z])" + // concentration denominator, e.g. "/1.5 mL"
      "|\\s*/\\s*1(?![\\d.])(?!\\s*[a-z\\[])" + // bare "/1" = per dose unit
      ")?"
  )

  // "10-325 mg" (common combination-product shorthand) -> "10 mg / 325 mg"
  private val RANGE_SHORTHAND = Regex("""(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*(mcg|mg)(?![a-z])""")

  /** Returns the parsed components, or null if no strength could be recognized. */
  fun parse(raw: String?): List<StrengthComponent>? {
    if (raw.isNullOrBlank()) return null
    val normalized = normalize(raw)
    val components = COMPONENT_REGEX.findAll(normalized).mapNotNull { match ->
      val value = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
      val numUnit = NUMERATOR_UNITS[match.groupValues[2]] ?: return@mapNotNull null
      val denUnitRaw = match.groupValues[4]
      if (denUnitRaw.isEmpty()) {
        StrengthComponent(value * numUnit.factor, numUnit.canonical, per = null)
      } else {
        val denUnit = DENOMINATOR_UNITS[denUnitRaw] ?: return@mapNotNull null
        val denValue = match.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
        val denCanonical = denValue * denUnit.factor
        if (denCanonical <= 0.0) return@mapNotNull null
        StrengthComponent(value * numUnit.factor / denCanonical, numUnit.canonical, denUnit.canonical)
      }
    }.toList()
    return components.ifEmpty { null }
  }

  /**
   * True only if both strengths describe the same set of ingredient amounts.
   * Unparseable strengths fall back to exact (whitespace/case-insensitive) text equality.
   */
  fun equivalent(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    val pa = parse(a)
    val pb = parse(b)
    if (pa == null || pb == null) {
      return fallbackKey(a) == fallbackKey(b)
    }
    if (pa.size != pb.size) return false
    val unmatched = pb.toMutableList()
    for (component in pa) {
      val idx = unmatched.indexOfFirst { componentsEqual(component, it) }
      if (idx < 0) return false
      unmatched.removeAt(idx)
    }
    return true
  }

  private fun componentsEqual(a: StrengthComponent, b: StrengthComponent): Boolean {
    if (a.unit != b.unit || a.per != b.per) return false
    val scale = max(abs(a.value), abs(b.value))
    if (scale == 0.0) return true
    return abs(a.value - b.value) <= RELATIVE_TOLERANCE * scale
  }

  private fun normalize(raw: String): String {
    var s = raw.lowercase()
      .replace('µ', 'u')
      .replace('μ', 'u')
    // Thousands separators ("1,000 mg") before decimal commas ("0,5 mg").
    s = s.replace(Regex("""(\d),(\d{3})(?!\d)"""), "$1$2")
    s = s.replace(Regex("""(\d),(\d)"""), "$1.$2")
    s = RANGE_SHORTHAND.replace(s) { m ->
      "${m.groupValues[1]} ${m.groupValues[3]} / ${m.groupValues[2]} ${m.groupValues[3]}"
    }
    return s
  }

  private fun fallbackKey(raw: String): String {
    return raw.lowercase().filterNot { it.isWhitespace() }.replace("/1ml", "/ml")
  }
}
