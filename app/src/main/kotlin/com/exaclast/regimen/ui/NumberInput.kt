package com.exaclast.regimen.ui

import com.exaclast.regimen.R
import com.exaclast.regimen.model.Message
import java.time.LocalDate
import java.util.Locale

/**
 * Strict parsing for user-entered numbers. Any text that can't be read unambiguously returns null,
 * so the UI can flag it. Callers must NEVER substitute a default for unparseable input:
 * "0,5" silently becoming 1.0 doubles a dose, and "4h" silently becoming null removes a
 * minimum-interval safety limit.
 */
object NumberInput {

  /**
   * An NDC as typed or pasted: only digits (0-9) and hyphens survive, so "NDC 00781-1852-20" and stray spaces
   * or letters don't reach the lookup. Not validated here; [com.exaclast.regimen.model.Medication.normalizeNdc11]
   * formats a complete one.
   */
  fun filterNdc(raw: String): String = raw.filter { it in '0'..'9' || it == '-' }

  /** An NPI as typed or pasted: digits (0-9) only, capped at 10 digits. */
  fun filterNpi(raw: String): String = raw.filter { it in '0'..'9' }.take(10)

  /**
   * An ICD-10 code as typed or pasted: uppercased ASCII letters, digits, and at most one
   * decimal point (not leading), capped at 8 characters (e.g. `E11.9`, `S72.001A`).
   */
  fun filterIcd10(raw: String): String = buildString {
    var hasDot = false
    for (ch in raw.uppercase(Locale.US)) {
      when {
        ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
        ch == '.' && !hasDot && isNotEmpty() -> {
          append(ch)
          hasDot = true
        }
      }
      if (length >= 8) break
    }
  }

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

    val value = when {
      DECIMAL.matches(t) -> {
        val raw = DECIMAL.matchEntire(t)!!.groupValues[1]
        // "1,000" is ambiguous (thousands separator vs. decimal comma): reject rather than guess.
        if (Regex("""^\d+,\d{3}$""").matches(raw)) return null
        raw.replace(',', '.').toDoubleOrNull()
      }
      MIXED.matches(t) -> {
        val m = MIXED.matchEntire(t)!!
        val whole = m.groupValues[1].toDoubleOrNull() ?: return null
        val num = m.groupValues[2].toDoubleOrNull() ?: return null
        val den = m.groupValues[3].toDoubleOrNull() ?: return null
        if (den == 0.0 || num >= den) return null
        whole + num / den
      }
      FRACTION.matches(t) -> {
        val m = FRACTION.matchEntire(t)!!
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val den = m.groupValues[2].toDoubleOrNull() ?: return null
        if (den == 0.0) return null
        num / den
      }
      WHOLE_PLUS_UNICODE.matches(t) -> {
        val m = WHOLE_PLUS_UNICODE.matchEntire(t)!!
        val whole = m.groupValues[1].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
        val frac = UNICODE_FRACTIONS[m.groupValues[2][0]] ?: return null
        whole + frac
      }
      else -> null
    }
    return value?.takeIf { it.isFinite() }
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
  data class Invalid(val message: Message) : FieldCheck<Nothing>

  val errorMessage: Message? get() = (this as? Invalid)?.message
  val isValid: Boolean get() = this is Valid
}

/** Required amount > 0. */
fun checkRequiredPositiveDecimal(text: String): FieldCheck<Double> {
  if (text.isBlank()) return FieldCheck.Invalid(Message(R.string.check_required))
  val v = NumberInput.parseDecimal(text) ?: return FieldCheck.Invalid(Message(R.string.check_number_example))
  return if (v > 0.0) FieldCheck.Valid(v) else FieldCheck.Invalid(Message(R.string.check_greater_than_zero))
}

/** Optional amount: blank → null; otherwise must be > 0. */
fun checkOptionalPositiveDecimal(text: String): FieldCheck<Double?> {
  if (text.isBlank()) return FieldCheck.Valid(null)
  val v = NumberInput.parseDecimal(text) ?: return FieldCheck.Invalid(Message(R.string.check_number_example_short))
  return if (v > 0.0) FieldCheck.Valid(v) else FieldCheck.Invalid(Message(R.string.check_greater_than_zero))
}

/** Optional whole number: blank → null; otherwise must be a positive integer. */
fun checkOptionalPositiveInt(text: String): FieldCheck<Int?> {
  if (text.isBlank()) return FieldCheck.Valid(null)
  val v = NumberInput.parsePositiveInt(text) ?: return FieldCheck.Invalid(Message(R.string.check_whole_number))
  return FieldCheck.Valid(v)
}

/** Required whole number ≥ 1. */
fun checkRequiredPositiveInt(text: String): FieldCheck<Int> {
  if (text.isBlank()) return FieldCheck.Invalid(Message(R.string.check_required))
  val v = NumberInput.parsePositiveInt(text) ?: return FieldCheck.Invalid(Message(R.string.check_whole_number_one_plus))
  return FieldCheck.Valid(v)
}
