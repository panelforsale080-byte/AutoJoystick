package com.axcel.autojoystick

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    // tolerant: (131,116), 131/116, 131 116, [131,116], {131, 116}
    val coordRegex = Regex("""[\[\(\{<]?\s*(\d{1,3})\s*[, /\-]\s*(\d{1,3})\s*[\]\)\}>]?""")

    data class Result(val coord: Pair<Int, Int>?, val rawText: String)

    fun recognizeCoord(bitmap: Bitmap, onResult: (Result) -> Unit) {
        try {
            val img = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(img)
                .addOnSuccessListener { res ->
                    var best: Pair<Int, Int>? = null
                    val raw = StringBuilder()
                    for (block in res.textBlocks) {
                        for (line in block.lines) {
                            if (raw.isNotEmpty()) raw.append('\n')
                            raw.append(line.text)
                            for (el in line.elements) {
                                val m = coordRegex.find(el.text) ?: continue
                                val xRaw = m.groupValues[1].toIntOrNull() ?: continue
                                val yRaw = m.groupValues[2].toIntOrNull() ?: continue
                                // Keep all valid 1–3 digit values. A previous
                                // heuristic stripped the leading 1 from valid
                                // coordinates such as "137,283)" when OCR
                                // dropped the opening parenthesis.
                                val x = xRaw
                                val y = yRaw
                                if (x in 0..400 && y in 0..400) {
                                    best = x to y; break
                                }
                            }
                            if (best != null) break
                        }
                        if (best != null) break
                    }
                    Log.d("AJOCR", "raw='${raw.toString().take(120)}' coord=$best")
                    onResult(Result(best, raw.toString()))
                }
                .addOnFailureListener { e ->
                    Log.w("AJOCR", "ocr fail: ${e.message}")
                    onResult(Result(null, ""))
                }
        } catch (t: Throwable) {
            Log.w("AJOCR", "throw: ${t.message}")
            onResult(Result(null, ""))
        }
    }
}
