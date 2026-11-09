package com.exaclast.regimen.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DrugBarcodeScanner {

  /** One place to choose the model. Stage/preference variants were tried and made no difference to refusals. */
  private fun newClient(): GenerativeModel = Generation.getClient()

  /**
   * [GenAiException.errorCode] AICore returns when its safety policy declines the request ("Request
   * doesn't pass certain policy check"). What trips it is not understood: it has refused labels for
   * unrelated drugs (e.g. doxycycline, Descovy) and accepted others.
   */
  private const val ERROR_CODE_POLICY_BLOCKED = 4

  const val POLICY_BLOCKED_MESSAGE =
    "The on-device AI declined to read this label. This sometimes happens even with a clear photo; " +
      "you can try again with a different photo, or enter the details manually."

  private fun Throwable.toUserFacing(): Throwable =
    if (this is GenAiException && errorCode == ERROR_CODE_POLICY_BLOCKED) {
      IllegalStateException(POLICY_BLOCKED_MESSAGE, this)
    } else {
      this
    }

  private fun <T> Result<T>.withUserFacingErrors(): Result<T> = recoverCatching { throw it.toUserFacing() }

  /**
   * Returns true if the current device supports on-device Gemini Nano via Android AICore
   * ([FeatureStatus.AVAILABLE], [FeatureStatus.DOWNLOADABLE], or [FeatureStatus.DOWNLOADING]).
   * Returns false on emulators or devices without AICore so the UI can disable the feature cleanly.
   */
  suspend fun isGeminiNanoAvailable(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val model = newClient()
      try {
        val status = model.checkStatus()
        status == FeatureStatus.AVAILABLE ||
          status == FeatureStatus.DOWNLOADABLE ||
          status == FeatureStatus.DOWNLOADING
      } finally {
        model.close()
      }
    }.getOrDefault(false)
  }

  /**
   * Checks the current [FeatureStatus] of on-device Gemini Nano from AICore.
   */
  suspend fun checkNanoStatus(): Int = withContext(Dispatchers.IO) {
    runCatching {
      val model = newClient()
      try {
        model.checkStatus()
      } finally {
        model.close()
      }
    }.getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  /**
   * Initiates the background model download for Gemini Nano via AICore.
   */
  suspend fun startNanoDownload(
    onProgress: (String) -> Unit = {},
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val model = newClient()
      try {
        val status = model.checkStatus()
        if (status == FeatureStatus.AVAILABLE) return@runCatching
        if (status != FeatureStatus.DOWNLOADABLE && status != FeatureStatus.DOWNLOADING) {
          throw IllegalStateException("Gemini Nano is not downloadable on this device ($status).")
        }
        onProgress("Downloading Gemini Nano via AICore...")
        model.download().collect { dl ->
          when (dl) {
            is DownloadStatus.DownloadStarted -> onProgress("Download started...")
            is DownloadStatus.DownloadProgress -> {
              val mb = dl.totalBytesDownloaded / (1024 * 1024)
              onProgress("Downloading ($mb MB)...")
            }
            is DownloadStatus.DownloadCompleted -> onProgress("Gemini Nano ready!")
            is DownloadStatus.DownloadFailed -> throw IllegalStateException(
              "Download failed: ${dl.e.localizedMessage ?: "Unknown error"}"
            )
          }
        }
      } finally {
        model.close()
      }
    }
  }

  /**
   * Opens Android AICore system app details or Developer Options AICore settings.
   */
  fun openAiCoreSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:com.google.android.aicore")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    val opened = runCatching {
      context.startActivity(intent)
      true
    }.getOrDefault(false)

    if (!opened) {
      val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      runCatching { context.startActivity(devIntent) }.getOrElse {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
      }
    }
  }

  /**
   * Creates a temporary FileProvider Uri in cacheDir for capturing a photo
   * of a medication bottle label.
   */
  fun createTempLabelPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "label_scans").apply { mkdirs() }
    val file = File(dir, "label_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file,
    )
  }

  /**
   * Max characters of recognized text sent to the model. The Prompt API takes about 4000 tokens in
   * total and the extraction prompt itself uses several hundred.
   */
  private const val MAX_LABEL_TEXT_CHARS = 6000

  /**
   * Reads one or more photos of the same label with on-device text recognition, then has Gemini Nano
   * structure the text. Sending text instead of the image is deliberate: AICore's safety check
   * refuses many label photos outright but accepts the same label as text. Several photos let the
   * user turn a small bottle whose label wraps around it.
   */
  suspend fun analyzeLabelFromPhotos(
    context: Context,
    imageUris: List<Uri>,
    userConfiguredPatientName: String? = null,
    onStatusUpdate: (String) -> Unit = {},
  ): Result<ParsedMedicationLabel> = withContext(Dispatchers.IO) {
    runCatching {
      onStatusUpdate(if (imageUris.size == 1) "Reading label text..." else "Reading label text from ${imageUris.size} photos...")
      val texts = LabelTextRecognizer.recognize(context, imageUris).map { it.trim() }.filter { it.isNotEmpty() }
      if (texts.isEmpty()) {
        throw IllegalStateException(
          "Couldn't read any text from the photo. Try better light, and hold the label flat and closer."
        )
      }
      val combined = if (texts.size == 1) {
        texts.single()
      } else {
        "(Text read from ${texts.size} photos of the same container, turned between shots. Lines may " +
          "repeat, be out of order or be misread near the edges. A word can be cut off at the edge of " +
          "one photo and continue in another, for example \"AMOXI\" in one and \"XICILLIN\" in the " +
          "next: when the end of one fragment overlaps the start of another, join them into the full word.)\n\n" +
          texts.joinToString("\n\n")
      }
      analyzeLabelFromText(
        rawLabelText = combined.take(MAX_LABEL_TEXT_CHARS),
        userConfiguredPatientName = userConfiguredPatientName,
        onStatusUpdate = onStatusUpdate,
      ).getOrThrow()
    }
  }

  /**
   * Analyzes pasted or transcribed medication label text using on-device Gemini Nano (AICore).
   */
  suspend fun analyzeLabelFromText(
    rawLabelText: String,
    userConfiguredPatientName: String? = null,
    onStatusUpdate: (String) -> Unit = {},
  ): Result<ParsedMedicationLabel> = withContext(Dispatchers.IO) {
    runCatching {
      val model = newClient()
      try {
        ensureModelReady(model, onStatusUpdate)
        onStatusUpdate("Analyzing label text with on-device Gemini Nano...")
        val request = generateContentRequest(
          TextPart(
            DrugLabelParser.buildLabelExtractionPrompt(
              additionalLabelText = rawLabelText,
              knownPatientName = userConfiguredPatientName,
            )
          ),
        ) {
          temperature = 0.1f
          maxOutputTokens = 650
        }
        val response = model.generateContent(request)
        val outputText = response.candidates.firstOrNull()?.text
          ?: throw IllegalStateException("Gemini Nano returned an empty response.")
        DrugLabelParser.parseGeminiJsonResponse(
          rawOutput = outputText,
          userConfiguredPatientName = userConfiguredPatientName,
        )
      } finally {
        model.close()
      }
    }.withUserFacingErrors()
  }

  private suspend fun ensureModelReady(
    model: GenerativeModel,
    onStatusUpdate: (String) -> Unit,
  ) {
    when (model.checkStatus()) {
      FeatureStatus.AVAILABLE -> return
      FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
        onStatusUpdate("Downloading on-device Gemini Nano model via AICore...")
        model.download().collect { status ->
          when (status) {
            is DownloadStatus.DownloadStarted ->
              onStatusUpdate("Downloading on-device Gemini Nano model...")
            is DownloadStatus.DownloadProgress ->
              onStatusUpdate("Downloading Gemini Nano (${status.totalBytesDownloaded / (1024 * 1024)} MB)...")
            is DownloadStatus.DownloadCompleted ->
              onStatusUpdate("Gemini Nano ready — analyzing label...")
            is DownloadStatus.DownloadFailed ->
              throw IllegalStateException(
                "Gemini Nano model download failed: ${status.e.localizedMessage ?: "Unknown error"}"
              )
          }
        }
      }
      else -> {
        throw IllegalStateException("On-device Gemini Nano (AICore) is not available on this device.")
      }
    }
  }
}
