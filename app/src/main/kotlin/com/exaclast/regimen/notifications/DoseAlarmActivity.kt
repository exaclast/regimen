package com.exaclast.regimen.notifications

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exaclast.regimen.data.RegimenRepository
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.ClockPreferences
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.theme.RegimenTheme
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Full-screen end-of-window alarm. Launched by the alarm notification's full-screen intent
 * ([ACTION_RING], rings) or by tapping that notification later ([ACTION_SHOW], silent).
 */
class DoseAlarmActivity : ComponentActivity() {

  private data class AlarmTarget(val bucketId: String?, val dateIso: String)

  private var target by mutableStateOf(AlarmTarget(null, LocalDate.now().toString()))
  private val ringTimeoutHandler = Handler(Looper.getMainLooper())
  private val stopRinging = Runnable { AlarmRingtonePlayer.stop() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    ClockPreferences.use24Hour = DateFormat.is24HourFormat(this)

    // Wake the screen and show over the lock screen (also declared in the manifest).
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Acting on a dose from the lock screen is the point of an alarm, so don't force an unlock —
    // unless the user asked to keep medication details off the lock screen.
    val repo = RegimenRepository.getInstance(applicationContext)
    if (repo.snapshot.value.settings.discreetNotifications) {
      getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    // Recreated after a configuration change: the alarm is already ringing (or was silenced).
    handleLaunch(intent, startRinging = savedInstanceState == null)

    setContent {
      RegimenTheme {
        DoseAlarmScreen(
          bucketId = target.bucketId,
          dateIso = target.dateIso,
          onTake = { items ->
            logAll(items, DoseStatus.TAKEN)
            finish()
          },
          onSnooze = { items, snoozeMinutes ->
            logAll(items, DoseStatus.SNOOZED, snoozeUntil = System.currentTimeMillis() + snoozeMinutes * 60_000L)
            finish()
          },
          onSkip = { items ->
            logAll(items, DoseStatus.SKIPPED)
            finish()
          },
          onNothingPending = ::silence,
          onDismiss = {
            silence()
            finish()
          },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLaunch(intent, startRinging = true)
  }

  private fun handleLaunch(intent: Intent, startRinging: Boolean) {
    val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID)
    val dateIso = intent.getStringExtra(EXTRA_DATE_ISO) ?: LocalDate.now().toString()
    target = AlarmTarget(bucketId, dateIso)

    if (startRinging && intent.action == ACTION_RING) {
      AlarmRingtonePlayer.play(this)
      ringTimeoutHandler.removeCallbacks(stopRinging)
      ringTimeoutHandler.postDelayed(stopRinging, RING_TIMEOUT_MILLIS)
      // This screen owns the audio now (volume keys silence it); stop the notification's
      // insistent copy so the two don't overlap and silencing actually silences.
      if (bucketId != null) {
        val snapshot = RegimenRepository.getInstance(applicationContext).snapshot.value
        DoseNotificationManager.silenceAlarmNotifications(applicationContext, snapshot, bucketId, dateIso)
      }
    }
  }

  /** Logging reconciles alarms and notifications (no manual cancelling needed here). */
  private fun logAll(items: List<ScheduledDoseItem>, status: DoseStatus, snoozeUntil: Long? = null) {
    silence()
    val repo = RegimenRepository.getInstance(applicationContext)
    items.forEach { item ->
      repo.logDose(
        medicationId = item.medication.id,
        scheduleId = item.schedule.id,
        bucketId = item.bucket.id,
        scheduledDateIso = item.dateIso,
        status = status,
        amountTaken = item.schedule.doseAmount,
        amountUnit = item.schedule.doseUnit,
        siteUsed = if (status == DoseStatus.SKIPPED) null else item.suggestedSite,
        snoozedUntilMillis = snoozeUntil,
        existingLogId = item.existingLog?.id,
      )
    }
  }

  private fun silence() {
    ringTimeoutHandler.removeCallbacks(stopRinging)
    AlarmRingtonePlayer.stop()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    // Volume keys silence the alarm without closing the screen.
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      if (AlarmRingtonePlayer.isPlaying) {
        silence()
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onDestroy() {
    super.onDestroy()
    if (!isChangingConfigurations) silence()
  }

  companion object {
    const val ACTION_RING = "com.exaclast.regimen.action.RING_DOSE_ALARM"
    const val ACTION_SHOW = "com.exaclast.regimen.action.SHOW_DOSE_ALARM"
    const val EXTRA_BUCKET_ID = "extra_bucket_id"
    const val EXTRA_DATE_ISO = "extra_date_iso"

    /** Like a clock alarm, stop ringing eventually; the notification stays until acted on. */
    private const val RING_TIMEOUT_MILLIS = 10 * 60_000L
  }
}

@Composable
fun DoseAlarmScreen(
  bucketId: String?,
  dateIso: String,
  onTake: (List<ScheduledDoseItem>) -> Unit,
  onSnooze: (List<ScheduledDoseItem>, Int) -> Unit,
  onSkip: (List<ScheduledDoseItem>) -> Unit,
  onNothingPending: () -> Unit,
  onDismiss: () -> Unit,
) {
  val repo = RegimenRepository.getInstance(androidx.compose.ui.platform.LocalContext.current)
  val snapshot by repo.snapshot.collectAsState()

  val board = remember(snapshot, dateIso) {
    DoseEngine.buildDailyBoard(
      dateIso = dateIso,
      medications = snapshot.medications,
      schedules = snapshot.schedules,
      buckets = snapshot.buckets,
      logs = snapshot.doseLogs,
      conditions = snapshot.conditions,
      providers = snapshot.providers,
    )
  }

  // Everything in this window still needing the user; doses snoozed past now have their own alarm.
  val activeItems = remember(board, bucketId) {
    val itemsInBucket = if (bucketId != null) {
      board.bucketGroups.find { it.bucket.id == bucketId }?.items.orEmpty()
    } else {
      board.bucketGroups.flatMap { it.items }
    }
    val nowMillis = System.currentTimeMillis()
    itemsInBucket.filter { DoseNotificationManager.isActionable(it, nowMillis) }
  }

  // Logged from the notification or the app while this screen was up: stop ringing.
  val nothingPending = activeItems.isEmpty()
  LaunchedEffect(nothingPending) {
    if (nothingPending) onNothingPending()
  }

  val bucket = remember(snapshot, bucketId, activeItems) {
    snapshot.buckets.find { it.id == bucketId } ?: activeItems.firstOrNull()?.bucket
  }

  var currentTimeText by remember { mutableStateOf(UiFormat.time(System.currentTimeMillis())) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(1000)
      currentTimeText = UiFormat.time(System.currentTimeMillis())
    }
  }

  // Pulsing animation for alarm urgency
  val infiniteTransition = rememberInfiniteTransition(label = "alarm_pulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.14f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "pulse_scale",
  )

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(16.dp))

      // Urgent animated pulsing bell
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(96.dp),
      ) {
        Box(
          modifier = Modifier
            .size(88.dp)
            .scale(pulseScale)
            .background(
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
              shape = CircleShape,
            )
        )
        Box(
          modifier = Modifier
            .size(72.dp)
            .background(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = CircleShape,
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.NotificationsActive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(36.dp),
          )
        }
      }

      Spacer(Modifier.height(16.dp))

      // Current Time
      Text(
        text = currentTimeText,
        style = MaterialTheme.typography.displayMedium.copy(
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )

      val bucketTitle = bucket?.name ?: "Dose Window"
      Text(
        text = "DOSE WINDOW ENDED • $bucketTitle",
        style = MaterialTheme.typography.labelLarge.copy(
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.2.sp,
        ),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
      )

      Spacer(Modifier.height(16.dp))

      if (activeItems.isEmpty()) {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "All doses for this window have already been recorded.",
              style = MaterialTheme.typography.bodyLarge,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(24.dp),
            )
          }
        }
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = onDismiss,
          modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
          shape = RoundedCornerShape(16.dp),
        ) {
          Text("Dismiss")
        }
      } else {
        // Items list
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(activeItems, key = { it.key }) { item ->
            AlarmItemCard(item = item)
          }
        }

        Spacer(Modifier.height(16.dp))

        // Action Buttons
        val count = activeItems.size
        val takeLabel = if (count == 1) "Take Dose" else "Take All ($count)"
        val snoozeLabel = "Snooze ${snapshot.settings.snoozeMinutes}m"
        val skipLabel = if (count == 1) "Skip Dose" else "Skip All"

        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = { onTake(activeItems) },
            modifier = Modifier
              .fillMaxWidth()
              .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(takeLabel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            FilledTonalButton(
              onClick = { onSnooze(activeItems, snapshot.settings.snoozeMinutes) },
              modifier = Modifier
                .weight(1f)
                .height(50.dp),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(snoozeLabel, maxLines = 1)
            }

            OutlinedButton(
              onClick = { onSkip(activeItems) },
              modifier = Modifier
                .weight(1f)
                .height(50.dp),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(skipLabel, maxLines = 1)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AlarmItemCard(item: ScheduledDoseItem) {
  val med = item.medication
  Card(
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = med.displayTitle,
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        if (med.strength.isNotBlank()) {
          Text(
            text = med.strength,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Spacer(Modifier.height(6.dp))

      Text(
        text = "Dose: ${item.formattedDoseInstruction()}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (!item.suggestedSite.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(4.dp))
          val lastInfo = item.lastSiteUsed?.let { " (Last: $it)" } ?: ""
          Text(
            text = "Site: ${item.suggestedSite}$lastInfo",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      med.injectableDetails?.let { inj ->
        val draw = inj.formattedDrawNeedle()?.let { "Draw: $it" }
        val inject = inj.formattedInjectNeedle()?.let { "Inject: $it" }
        val needles = listOfNotNull(draw, inject).joinToString(" • ")
        if (needles.isNotEmpty()) {
          Spacer(Modifier.height(4.dp))
          Text(
            text = needles,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
          )
        }
      }
    }
  }
}

