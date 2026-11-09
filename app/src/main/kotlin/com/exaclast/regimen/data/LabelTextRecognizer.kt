package com.exaclast.regimen.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the printed text off label photos with ML Kit's on-device text recognizer.
 *
 * It only transcribes. Working out which words are the drug, strength and directions is left to
 * Gemini Nano, so no label layout or drug names are hardcoded here. The AICore safety filter that
 * refuses some label photos does not see the image this way, only the recognized text.
 */
object LabelTextRecognizer {

  /** Text recognized in each photo, in the order given. A photo with no readable text yields "". */
  suspend fun recognize(context: Context, imageUris: List<Uri>): List<String> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      return imageUris.map { uri ->
        val image = InputImage.fromFilePath(context, uri)
        suspendCancellableCoroutine { cont ->
          recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
        }
      }
    } finally {
      recognizer.close()
    }
  }
}
