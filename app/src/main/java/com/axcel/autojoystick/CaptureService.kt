package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Foreground service that owns the MediaProjection (required by Android 10+).
 * Grabs frames, OCRs the minimap coordinate region, feeds JoystickController.
 */
class CaptureService : Service() {

    companion object {
        var pendingResultCode: Int = 0
        var pendingData: Intent? = null
        @Volatile var running: Boolean = false
    }

    private var projection: MediaProjection? = null
    private var vdisp: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notif = Notification.Builder(this, "autojoy")
            .setContentTitle("AutoJoystick capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notif)
        }

        val code = pendingResultCode
        val data = pendingData
        if (data == null || code == 0) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels; screenH = dm.heightPixels

        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        vdisp = projection?.createVirtualDisplay(
            "ajcap", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )

        running = true
        thread = HandlerThread("ajcap").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.post(captureLoop)
        return START_STICKY
    }

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try { grabAndOcr() } catch (_: Throwable) {}
            handler?.postDelayed(this, 700)
        }
    }

    private fun grabAndOcr() {
        val r = reader ?: return
        val img = r.acquireLatestImage() ?: return
        val w = img.width; val h = img.height
        val plane = img.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val rowPadding = rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        img.close()
        val full = Bitmap.createBitmap(bmp, 0, 0, w, h)
        bmp.recycle()

        // Minimap region: top-right quadrant (where "Lorencia (131,116)" style coord renders)
        val rect = CoordinateCalibrator.coordRectFor(w, h)
        if (rect.width() <= 0 || rect.height() <= 0) { full.recycle(); return }
        val crop = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
        full.recycle()

        OcrEngine.recognizeCoord(crop) { coord ->
            crop.recycle()
            if (coord != null) {
                JoystickController.currentCoord = coord
                OverlayBus.push("${coord.first},${coord.second}")
                JoystickController.onPositionUpdate(coord.first, coord.second)
            }
        }
    }

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        try { vdisp?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel("autojoy") == null) {
                nm.createNotificationChannel(
                    NotificationChannel("autojoy", "AutoJoystick", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
