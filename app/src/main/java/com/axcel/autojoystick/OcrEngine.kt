package com.axcel.autojoystick

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    // tolerant: (131,116), 131/116, 131 116, [131,116], {131, 116}
    val coordRegex = Regex("""[\[\(\{<]?\s*(\d{1,3})\s*[, /\-]\s*(\d{1,3})\s*[\]\)\}>]?""")

    data class Result(val coord: Pair<Int, Int>?, val rawText: String)
    private var lastAcceptedCoord: Pair<Int, Int>? = null
    private var repeatedSuspiciousCoord: Pair<Int, Int>? = null

    fun resetTracking() {
        synchronized(this) {
            lastAcceptedCoord = null
            repeatedSuspiciousCoord = null
        }
    }

    /**
     * A missing opening parenthesis can make "(48,29)" appear as "148,29".
     * Keep valid 3-digit coordinates, but reject one isolated +100 X jump with
     * nearly unchanged Y. A repeated candidate is accepted as a real movement.
     */
    private fun stabilize(candidate: Pair<Int, Int>): Pair<Int, Int> {
        synchronized(this) {
            val previous = lastAcceptedCoord
            if (previous != null &&
                candidate.first == previous.first + 100 &&
                candidate.first in 100..199 &&
                abs(candidate.second - previous.second) <= 3
            ) {
                if (repeatedSuspiciousCoord == candidate) {
                    lastAcceptedCoord = candidate
                    repeatedSuspiciousCoord = null
                    return candidate
                }
                repeatedSuspiciousCoord = candidate
                return previous
            }
            repeatedSuspiciousCoord = null
            lastAcceptedCoord = candidate
            return candidate
        }
    }

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
                                val y = yRaw
                                val candidate = stabilize(xRaw to y)
                                if (candidate.first in 0..400 && candidate.second in 0..400) {
                                    best = candidate; break
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
