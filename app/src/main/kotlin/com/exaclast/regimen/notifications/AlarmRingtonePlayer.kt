package com.exaclast.regimen.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Handles persistent audio and haptic feedback for end-of-window dose alarms.
 * Uses [AudioAttributes.USAGE_ALARM] so playback respects the system alarm volume
 * and pierces Do Not Disturb.
 */
object AlarmRingtonePlayer {

  private val executor = Executors.newSingleThreadExecutor()
  @Volatile
  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var audioManager: AudioManager? = null
  private var focusRequest: AudioFocusRequest? = null
  private var playGeneration: Long = 0L

  val isPlaying: Boolean
    get() = mediaPlayer?.isPlaying == true

  @Synchronized
  fun play(context: Context) {
    stop()
    val generation = ++playGeneration
    val appContext = context.applicationContext
    audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val alarmAttrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

    // 1. Audio Focus
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
      .setAudioAttributes(alarmAttrs)
      .build()
    focusRequest = request
    audioManager?.requestAudioFocus(request)

    // 2. Alarm Ringtone Playback: prepare off the main thread and release any failed attempt.
    val candidates = listOfNotNull(
      RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM),
      Settings.System.DEFAULT_ALARM_ALERT_URI,
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    )
    executor.execute {
      for (uri in candidates) {
        synchronized(this) {
          if (generation != playGeneration) return@execute
        }
        var player: MediaPlayer? = null
        val prepared = try {
          player = MediaPlayer().apply {
            setDataSource(appContext, uri)
            setAudioAttributes(alarmAttrs)
            isLooping = true
            prepare()
          }
          true
        } catch (_: Exception) {
          runCatching { player?.release() }
          false
        }
        if (!prepared || player == null) continue

        synchronized(this) {
          if (generation != playGeneration) {
            runCatching { player.release() }
            return@execute
          }
          val started = runCatching {
            player.start()
            true
          }.getOrDefault(false)
          if (started) {
            mediaPlayer = player
            return@execute
          } else {
            runCatching { player.release() }
          }
        }
      }
    }

    // 3. Vibration Pattern (vibrate 600ms, pause 400ms). Tagged as an alarm so it isn't
    // suppressed by silent/vibrate-off ringer modes the way plain vibrations can be.
    runCatching {
      val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      vibrator = vm?.defaultVibrator

      val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 400), 0)
      vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }
  }

  @Synchronized
  fun stop() {
    playGeneration++
    runCatching {
      mediaPlayer?.let {
        if (it.isPlaying) {
          it.stop()
        }
        it.release()
      }
    }
    mediaPlayer = null

    runCatching {
      vibrator?.cancel()
    }
    vibrator = null

    runCatching {
      focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }
    focusRequest = null
    audioManager = null
  }
}

