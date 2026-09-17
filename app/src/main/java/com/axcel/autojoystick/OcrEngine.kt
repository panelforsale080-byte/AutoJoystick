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
    private val looseCoordRegex =
        Regex("""(?<!\d)(\d{1,3})\s*[,;:/\-]\s*(\d{1,3})(?!\d)""")
    private val spacedCoordRegex =
        Regex("""(?<!\d)(\d{1,3})\s+(\d{1,3})(?!\d)""")

    data class Result(val coord: Pair<Int, Int>?, val rawText: String)

    /** ML Kit sometimes reads the opening "(" of "(28,24)" as a "1" -> "128,24".
     *  Rule: strip a leading "1" from a 3-digit x in 100..199 ONLY when the element
     *  has a closing ")" but the digit-run is not preceded by "(" — meaning the "("
     *  itself was misread. Genuine 3-digit coords like 140,29 (no stray paren, or a
     *  real "(" before them) are left untouched. */
    fun fixLeadingParenOne(elText: String, x: Int): Int {
        if (x !in 100..199) return x
        val digits = x.toString()
        val idx = elText.indexOf(digits)
        val before = if (idx > 0) elText[idx - 1] else ' '
        val hasClose = elText.contains(')') || elText.contains(']') || elText.contains('}')
        val hasOpenBefore = before == '(' || before == '[' || before == '{' || elText.take(idx).contains('(')
        return if (hasClose && !hasOpenBefore) x % 100 else x
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

                            // Prefer element matches, but fall back to the full
                            // line. ML Kit can split "(49,5)" into separate
                            // elements such as "(49" and "5)", which used to
                            // make the controller miss every arrival update.
                            for (el in line.elements) {
                                best = parseCandidate(el.text)
                                if (best != null) break
                            }
                            if (best == null) {
                                best = parseCandidate(line.text)
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

    private fun parseCandidate(text: String): Pair<Int, Int>? {
        val match = coordRegex.find(text)
            ?: looseCoordRegex.find(text)
            ?: spacedCoordRegex.find(text)
            ?: return null
        val xRaw = match.groupValues[1].toIntOrNull() ?: return null
        val y = match.groupValues[2].toIntOrNull() ?: return null
        val x = fixLeadingParenOne(text, xRaw)
        return if (x in 0..400 && y in 0..400) x to y else null
    }
}
