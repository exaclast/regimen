package com.exaclast.regimen.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberInputTest {

  @Test
  fun parseDecimal_acceptsCommonDoseNotations() {
    mapOf(
      "1" to 1.0,
      "0.5" to 0.5,
      "0,5" to 0.5,
      ".5" to 0.5,
      " 2.25 " to 2.25,
      "1/2" to 0.5,
      "1 1/2" to 1.5,
      "½" to 0.5,
      "1½" to 1.5,
      "100" to 100.0,
    ).forEach { (text, expected) ->
      assertEquals(text, expected, NumberInput.parseDecimal(text)!!, 1e-9)
    }
  }

  @Test
  fun parseDecimal_rejectsAnythingAmbiguousInsteadOfGuessing() {
    listOf("", "  ", "4h", "two", "1.2.3", "1/0", "1 3/2", "1,000", "-1", "5 mg", "1e3").forEach { text ->
      assertNull(text, NumberInput.parseDecimal(text))
    }
  }

  @Test
  fun fieldChecks_neverSubstituteDefaults() {
    assertFalse(checkRequiredPositiveDecimal("").isValid)
    assertFalse(checkRequiredPositiveDecimal("0").isValid)
    assertEquals(FieldCheck.Valid(0.5), checkRequiredPositiveDecimal("0,5"))

    assertEquals(FieldCheck.Valid(null), checkOptionalPositiveDecimal(""))
    assertFalse("4h must not become 'no limit'", checkOptionalPositiveDecimal("4h").isValid)
    assertEquals(FieldCheck.Valid(4.0), checkOptionalPositiveDecimal("4"))

    assertEquals(FieldCheck.Valid(25), checkOptionalPositiveInt("25"))
    assertFalse(checkOptionalPositiveInt("25.5").isValid)
    assertFalse(checkRequiredPositiveInt("0").isValid)
    assertTrue(checkRequiredPositiveInt("7").isValid)
  }

  @Test
  fun parseIsoDate_isStrict() {
    assertEquals(LocalDate.of(2026, 11, 3), NumberInput.parseIsoDate("2026-11-03"))
    assertNull(NumberInput.parseIsoDate("11/03/2026"))
    assertNull(NumberInput.parseIsoDate("2026-13-01"))
    assertNull(NumberInput.parseIsoDate("2026-2-3"))
  }
}
