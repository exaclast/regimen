package com.exaclast.regimen.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DrugBarcodeScanner {

  /**
   * Returns true if the current device supports on-device Gemini Nano via Android AICore
   * ([FeatureStatus.AVAILABLE], [FeatureStatus.DOWNLOADABLE], or [FeatureStatus.DOWNLOADING]).
   * Returns false on emulators or devices without AICore so the UI can disable the feature cleanly.
   */
  suspend fun isGeminiNanoAvailable(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      val model = Generation.getClient()
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
      val model = Generation.getClient()
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
      val model = Generation.getClient()
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
   * Analyzes a captured or selected photo of a medication label using on-device Gemini Nano (AICore)
   * with a multimodal [ImagePart] + structured JSON prompt.
   */
  suspend fun analyzeLabelFromImageUri(
    context: Context,
    imageUri: Uri,
    onStatusUpdate: (String) -> Unit = {},
  ): Result<ParsedMedicationLabel> = withContext(Dispatchers.IO) {
    runCatching {
      val bitmap = loadScaledBitmap(context, imageUri, maxDimension = 1280)
      val model = Generation.getClient()
      try {
        ensureModelReady(model, onStatusUpdate)
        onStatusUpdate("Analyzing label with on-device Gemini Nano...")
        val request = generateContentRequest(
          ImagePart(bitmap),
          TextPart(DrugLabelParser.buildLabelExtractionPrompt()),
        ) {
          temperature = 0.1f
          maxOutputTokens = 400
        }
        val response = model.generateContent(request)
        val outputText = response.candidates.firstOrNull()?.text
          ?: throw IllegalStateException("Gemini Nano returned an empty response.")
        DrugLabelParser.parseGeminiJsonResponse(outputText)
      } finally {
        model.close()
      }
    }
  }

  /**
   * Analyzes pasted or transcribed medication label text using on-device Gemini Nano (AICore).
   */
  suspend fun analyzeLabelFromText(
    rawLabelText: String,
    onStatusUpdate: (String) -> Unit = {},
  ): Result<ParsedMedicationLabel> = withContext(Dispatchers.IO) {
    runCatching {
      val model = Generation.getClient()
      try {
        ensureModelReady(model, onStatusUpdate)
        onStatusUpdate("Analyzing label text with on-device Gemini Nano...")
        val request = generateContentRequest(
          TextPart(DrugLabelParser.buildLabelExtractionPrompt(rawLabelText)),
        ) {
          temperature = 0.1f
          maxOutputTokens = 400
        }
        val response = model.generateContent(request)
        val outputText = response.candidates.firstOrNull()?.text
          ?: throw IllegalStateException("Gemini Nano returned an empty response.")
        DrugLabelParser.parseGeminiJsonResponse(outputText)
      } finally {
        model.close()
      }
    }
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

  @Suppress("DEPRECATION")
  private fun loadScaledBitmap(
    context: Context,
    imageUri: Uri,
    maxDimension: Int,
  ): Bitmap {
    val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val source = ImageDecoder.createSource(context.contentResolver, imageUri)
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val longest = maxOf(w, h)
        if (longest > maxDimension) {
          val scale = maxDimension.toFloat() / longest.toFloat()
          decoder.setTargetSize(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
          )
        }
      }
    } else {
      MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
    }

    val longest = maxOf(rawBitmap.width, rawBitmap.height)
    if (longest <= maxDimension) return rawBitmap
    val scale = maxDimension.toFloat() / longest.toFloat()
    return Bitmap.createScaledBitmap(
      rawBitmap,
      (rawBitmap.width * scale).toInt().coerceAtLeast(1),
      (rawBitmap.height * scale).toInt().coerceAtLeast(1),
      true,
    )
  }
}
