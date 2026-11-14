package com.exaclast.regimen.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.exaclast.regimen.domain.PacingEvaluation
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.Labeled
import com.exaclast.regimen.model.texts

/** The user-visible name of an enum value, in the current language. */
@Composable
fun Labeled.label(): String = stringResource(labelRes)

/** The same outside Compose, from the [Resources] the caller holds. */
fun Resources.label(value: Labeled): String = getString(value.labelRes)

/** "20 units (0.2 mL) • SC", in the current language. */
@Composable
fun ScheduledDoseItem.doseText(): String = formattedDoseInstruction(LocalResources.current.texts())

/** Why a dose can't be taken yet, or null when it can. */
@Composable
fun PacingEvaluation.warning(): String? = warningText(LocalResources.current.texts())

/** When the next dose is allowed ("6:00 PM", "tomorrow at 6:00 PM"), or null. */
@Composable
fun PacingEvaluation.nextAllowed(): String? = nextAllowedText(LocalResources.current.texts())
