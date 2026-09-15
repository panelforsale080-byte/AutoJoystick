package com.axcel.autojoystick

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val coordRegex = Regex("""\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")

    fun recognizeCoord(bitmap: Bitmap, onResult: (String?) -> Unit) {
        try {
            val img = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(img)
                .addOnSuccessListener { result ->
                    val match = coordRegex.find(result.text)
                    val coord = match?.let { "(${it.groupValues[1]},${it.groupValues[2]})" }
                    onResult(coord)
                }
                .addOnFailureListener { e ->
                    Log.w("AutoJoystick", "ocr fail: ${e.message}")
                    onResult(null)
                }
        } catch (e: Throwable) {
            onResult(null)
        }
    }
}
