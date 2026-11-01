package com.exaclast.regimen.data

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

object DrugBarcodeScanner {

  private fun buildScannerOptions(): GmsBarcodeScannerOptions {
    return GmsBarcodeScannerOptions.Builder()
      .setBarcodeFormats(
        Barcode.FORMAT_DATA_MATRIX,
        Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_EAN_8,
        Barcode.FORMAT_CODE_128,
        Barcode.FORMAT_QR_CODE,
      )
      .enableAutoZoom()
      .build()
  }

  /**
   * Launches Google's native system camera barcode/DataMatrix scanner sheet.
   * Requires zero camera permissions in the app manifest.
   * If the scanner module is not yet installed or unavailable (e.g. on an emulator without Play Services),
   * triggers background module installation and invokes [onUnavailable] so the UI can show a fallback input dialog.
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
          // Request module download in background and immediately offer manual/paste fallback
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
}

