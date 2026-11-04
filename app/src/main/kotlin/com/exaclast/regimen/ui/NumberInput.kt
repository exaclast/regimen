package com.exaclast.regimen.ui

import java.time.LocalDate

/**
 * Strict parsing for user-entered numbers. Any text that can't be read unambiguously returns null,
 * so the UI can flag it. Callers must NEVER substitute a default for unparseable input:
 * "0,5" silently becoming 1.0 doubles a dose, and "4h" silently becoming null removes a
 * minimum-interval safety limit.
 */
object NumberInput {

  private val UNICODE_FRACTIONS = mapOf(
    '½' to 0.5,
    '¼' to 0.25,
    '¾' to 0.75,
    '⅓' to 1.0 / 3.0,
    '⅔' to 2.0 / 3.0,
    '⅛' to 0.125,
  )

  private val DECIMAL = Regex("""^(\d+(?:[.,]\d+)?|[.,]\d+)$""")
  private val FRACTION = Regex("""^(\d+)\s*/\s*(\d+)$""")
  private val MIXED = Regex("""^(\d+)\s+(\d+)\s*/\s*(\d+)$""")
  private val WHOLE_PLUS_UNICODE = Regex("""^(\d*)\s*([½¼¾⅓⅔⅛])$""")

  /**
   * Parses a non-negative decimal amount. Accepts `0.5`, `0,5`, `.5`, `1/2`, `1 1/2`, `½`, `1½`.
   * Returns null for blank, malformed, or ambiguous text (e.g. `1,000`, `4h`, `1/0`).
   */
  fun parseDecimal(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null

    DECIMAL.matchEntire(t)?.let { m ->
      val raw = m.groupValues[1]
      // "1,000" is ambiguous (thousands separator vs. decimal comma): reject rather than guess.
      if (Regex("""^\d+,\d{3}$""").matches(raw)) return null
      return raw.replace(',', '.').toDoubleOrNull()
    }
    MIXED.matchEntire(t)?.let { m ->
      val whole = m.groupValues[1].toDouble()
      val num = m.groupValues[2].toDouble()
      val den = m.groupValues[3].toDouble()
      if (den == 0.0 || num >= den) return null
      return whole + num / den
    }
    FRACTION.matchEntire(t)?.let { m ->
      val num = m.groupValues[1].toDouble()
      val den = m.groupValues[2].toDouble()
      if (den == 0.0) return null
      return num / den
    }
    WHOLE_PLUS_UNICODE.matchEntire(t)?.let { m ->
      val whole = m.groupValues[1].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
      val frac = UNICODE_FRACTIONS[m.groupValues[2][0]] ?: return null
      return whole + frac
    }
    return null
  }

  /** Parses a positive whole number (digits only). */
  fun parsePositiveInt(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || !t.all { it.isDigit() }) return null
    return t.toIntOrNull()?.takeIf { it > 0 }
  }

  /** Parses a strict ISO-8601 calendar date (`yyyy-MM-dd`). */
  fun parseIsoDate(text: String): LocalDate? {
    val t = text.trim()
    if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(t)) return null
    return runCatching { LocalDate.parse(t) }.getOrNull()
  }
}

/** Validation result for one form field. */
sealed interface FieldCheck<out T> {
  data class Valid<T>(val value: T) : FieldCheck<T>
  data class Invalid(val message: String) : FieldCheck<Nothing>

  val errorMessage: String? get() = (this as? Invalid)?.message
  val isValid: Boolean get() = this is Valid
}

/** Required amount > 0. */
fun checkRequiredPositiveDecimal(text: String): FieldCheck<Double> {
  if (text.isBlank()) return FieldCheck.Invalid("Required")
  val v = NumberInput.parseDecimal(text) ?: return FieldCheck.Invalid("Enter a number, e.g. 0.5 or 1/2")
  return if (v > 0.0) FieldCheck.Valid(v) else FieldCheck.Invalid("Must be greater than 0")
}

/** Optional amount: blank → null; otherwise must be > 0. */
fun checkOptionalPositiveDecimal(text: String): FieldCheck<Double?> {
  if (text.isBlank()) return FieldCheck.Valid(null)
  val v = NumberInput.parseDecimal(text) ?: return FieldCheck.Invalid("Enter a number, e.g. 4 or 0.5")
  return if (v > 0.0) FieldCheck.Valid(v) else FieldCheck.Invalid("Must be greater than 0")
}

/** Optional whole number: blank → null; otherwise must be a positive integer. */
fun checkOptionalPositiveInt(text: String): FieldCheck<Int?> {
  if (text.isBlank()) return FieldCheck.Valid(null)
  val v = NumberInput.parsePositiveInt(text) ?: return FieldCheck.Invalid("Enter a whole number")
  return FieldCheck.Valid(v)
}

/** Required whole number ≥ 1. */
fun checkRequiredPositiveInt(text: String): FieldCheck<Int> {
  if (text.isBlank()) return FieldCheck.Invalid("Required")
  val v = NumberInput.parsePositiveInt(text) ?: return FieldCheck.Invalid("Enter a whole number of 1 or more")
  return FieldCheck.Valid(v)
}
