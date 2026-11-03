package com.exaclast.regimen.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

object DrugBarcodeScanner {

  private fun buildScannerOptions(): GmsBarcodeScannerOptions {
    return GmsBarcodeScannerOptions.Builder()
      .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
      .enableAutoZoom()
      .build()
  }

  /**
   * Launches Google's native system camera barcode/DataMatrix scanner sheet
   * with ALL 1D/2D barcode formats enabled (DataMatrix, UPC-A/E, EAN-13, Code 39, ITF, Codabar, Code 128, PDF417).
   */
  fun startScan(
    context: Context,
    onScanned: (String) -> Unit,
    onCanceled: () -> Unit = {},
    onUnavailable: (String) -> Unit,
  ) {
    val scanner = GmsBarcodeScanning.getClient(context, buildScannerOptions())
    val moduleInstallClient = ModuleInstall.getClient(context)

    moduleInstallClient
      .areModulesAvailable(scanner)
      .addOnSuccessListener { response ->
        if (response.areModulesAvailable()) {
          scanner.startScan()
            .addOnSuccessListener { barcode ->
              val raw = barcode.rawValue?.takeIf { it.isNotBlank() } ?: barcode.displayValue
              if (!raw.isNullOrBlank()) {
                onScanned(raw)
              } else {
                onUnavailable("Scanned barcode did not contain readable text.")
              }
            }
            .addOnCanceledListener {
              onCanceled()
            }
            .addOnFailureListener { e ->
              onUnavailable(e.localizedMessage ?: "Camera barcode scanner unavailable.")
            }
        } else {
          val installRequest = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .build()
          moduleInstallClient.installModules(installRequest)
          onUnavailable("Downloading Google Barcode Scanner module in background—you can paste or test a barcode now.")
        }
      }
      .addOnFailureListener { e ->
        onUnavailable(e.localizedMessage ?: "Google Play Services barcode scanner is not available on this device.")
      }
  }

  /**
   * Creates a temporary FileProvider Uri in cacheDir for capturing a full-resolution photo
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
   * Runs on-device ML Kit Text Recognition (OCR) on a captured or selected photo of a medication label,
   * then parses the extracted text into a [ParsedMedicationLabel].
   */
  fun recognizeLabelFromImageUri(
    context: Context,
    imageUri: Uri,
    onResult: (ParsedMedicationLabel) -> Unit,
    onError: (String) -> Unit,
  ) {
    val image = runCatching {
      InputImage.fromFilePath(context, imageUri)
    }.getOrElse { err ->
      onError("Could not read label image: ${err.localizedMessage}")
      return
    }

    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
      .addOnSuccessListener { visionText ->
        val rawText = visionText.text
        if (rawText.isBlank()) {
          onError("No readable text detected on the label. Try moving closer with good lighting.")
        } else {
          onResult(DrugLabelParser.parseLabelText(rawText))
        }
      }
      .addOnFailureListener { e ->
        onError(
          e.localizedMessage
            ?: "On-device OCR module is still downloading from Google Play Services. Try again in a moment."
        )
      }
  }
}
