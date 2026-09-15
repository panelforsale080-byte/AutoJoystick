package com.axcel.autojoystick

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    // Matches "(131,116)" or "131,116" style coords
    private val coordRegex = Regex("""\(?\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)?""")

    fun recognizeCoord(bitmap: Bitmap, onResult: (Pair<Int, Int>?) -> Unit) {
        try {
            val img = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(img)
                .addOnSuccessListener { result ->
                    var best: Pair<Int, Int>? = null
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val m = coordRegex.find(line.text)
                            if (m != null) {
                                val x = m.groupValues[1].toIntOrNull()
                                val y = m.groupValues[2].toIntOrNull()
                                if (x != null && y != null && x <= 300 && y <= 300) {
                                    best = x to y
                                }
                            }
                        }
                    }
                    onResult(best)
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
