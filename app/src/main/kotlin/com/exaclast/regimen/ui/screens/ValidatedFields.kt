package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.exaclast.regimen.ui.FieldCheck

/**
 * Numeric text field that shows its own validation error. Pair with a [FieldCheck] from
 * [com.exaclast.regimen.ui.NumberInput] helpers and keep the form's Save button disabled while any
 * check is invalid; never fall back to a default value for unparseable input.
 */
@Composable
internal fun ValidatedNumberField(
  value: String,
  onValueChange: (String) -> Unit,
  check: FieldCheck<*>,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: (@Composable () -> Unit)? = null,
  keyboardType: KeyboardType = KeyboardType.Decimal,
  singleLine: Boolean = true,
) {
  val error = check.errorMessage
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    placeholder = placeholder,
    singleLine = singleLine,
    isError = error != null,
    supportingText = error?.let { msg -> { Text(msg) } },
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
    modifier = modifier,
  )
}

internal fun <T> FieldCheck<T>.validOrNull(): T? = (this as? FieldCheck.Valid<T>)?.value
