package com.exaclast.regimen.model

import com.exaclast.regimen.ui.NumberInput
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatNumTest {

  @Test
  fun floatingPointNoiseIsNotDisplayed() {
    assertEquals("7", formatNum(14.0 / 200.0 * 100.0))
    assertEquals("0.3", formatNum(0.1 + 0.2))
  }

  @Test
  fun longDecimalsRoundToThreePlaces() {
    assertEquals("1.667", formatNum(50.0 / 30.0))
    assertEquals("0.333", formatNum(1.0 / 3.0))
    assertEquals("1234.568", formatNum(1234.5678))
  }

  @Test
  fun smallVolumesKeepTheirSignificantDigits() {
    assertEquals("0.0015", formatNum(0.0015))
    assertEquals("0.0004", formatNum(0.0004))
    assertEquals("0.000123", formatNum(0.000123456))
  }

  @Test
  fun ordinaryValuesAreUnchanged() {
    assertEquals("0", formatNum(0.0))
    assertEquals("1", formatNum(1.0))
    assertEquals("100", formatNum(100.0))
    assertEquals("3000", formatNum(3000.0))
    assertEquals("0.5", formatNum(0.5))
    assertEquals("0.25", formatNum(0.25))
    assertEquals("2.5", formatNum(2.5))
  }

  @Test
  fun nonFiniteValuesDoNotCrash() {
    assertEquals("Infinity", formatNum(Double.POSITIVE_INFINITY))
    assertEquals("NaN", formatNum(Double.NaN))
  }

  @Test
  fun minimumIntervalSurvivesTheEditorRoundTrip() {
    // The editor shows minutes as rounded hours, then converts the text back to minutes on save.
    for (minutes in 1..2880) {
      val text = formatNum(minutes / 60.0)
      val parsed = checkNotNull(NumberInput.parseDecimal(text)) { "Unparseable: $text" }
      assertEquals("$minutes min shown as $text", minutes, (parsed * 60.0).roundToInt())
    }
  }
}
