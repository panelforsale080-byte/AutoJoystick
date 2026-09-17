package com.axcel.autojoystick

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object ImageUtil {
    fun applyPreprocess(src: Bitmap, contrast: Float, brightness: Int, invert: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val factor = if (invert) -contrast else contrast
        val offset = if (invert) 255f - brightness else brightness.toFloat()
        val cm = ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, offset,
                0f, factor, 0f, 0f, offset,
                0f, 0f, factor, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }
}
