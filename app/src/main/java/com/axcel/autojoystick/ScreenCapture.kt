package com.axcel.autojoystick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher

class ScreenCapture(private val activity: Activity) {

    private var projection: MediaProjection? = null
    private var vdisp: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun startProjection(launcher: ActivityResultLauncher<Intent>, code: Int, data: Intent?) {
        if (data == null) return
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        startVirtual()
    }

    private fun startVirtual() {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
        val w = dm.widthPixels; val h = dm.heightPixels; val d = dm.densityDpi

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vdisp = projection?.createVirtualDisplay(
            "autojoy", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    fun grabTopRight(rect: Rect): Bitmap? {
        val r = reader ?: return null
        val img = r.acquireLatestImage() ?: return null
        val w = img.width; val h = img.height
        val plane = img.planes[0]
        val buffer = plane.buffer
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        img.close()
        return Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
    }
}
