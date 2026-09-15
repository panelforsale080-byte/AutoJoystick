package com.axcel.autojoystick

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object ImageUtil {
    fun applyPreprocess(src: Bitmap, contrast: Float, brightness: Int, invert: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness.toFloat(),
                0f, contrast, 0f, 0f, brightness.toFloat(),
                0f, 0f, contrast, 0f, brightness.toFloat(),
                0f, 0f, 0f, 1f, if (invert) 255f else 0f
            )
        )
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        if (invert) {
            // second pass: invert via separate channel for sharper text
            cm.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
            val inv = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(inv).drawBitmap(out, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            out.recycle()
            return inv
        }
        return out
    }
}
