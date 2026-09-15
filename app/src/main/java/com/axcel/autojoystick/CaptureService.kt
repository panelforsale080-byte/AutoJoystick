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
import android.util.Log
import android.view.WindowManager
import android.graphics.Rect

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
    private lateinit var prefs: PreferenceStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            prefs = PreferenceStore(this)
            ensureChannel()
            val notif = Notification.Builder(this, "autojoy")
                .setContentTitle("AutoJoystick capture").setSmallIcon(android.R.drawable.ic_menu_camera).build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(2, notif)
            val code = pendingResultCode; val data = pendingData
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
            handler?.post(loop)
        } catch (t: Throwable) {
            Log.e("AJ", "capture start crash", t); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    private val loop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try { grabAndOcr() } catch (t: Throwable) { Log.w("AJ", "grab: ${t.message}") }
            handler?.postDelayed(this, 750)
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
        bmp.copyPixelsFromBuffer(buffer); img.close()
        val full = Bitmap.createBitmap(bmp, 0, 0, w, h); bmp.recycle()

        // Use user-calibrated ROI rect when available, scaled from capture space to frame space.
        val roi = effectiveRect(w, h)
        if (roi.width() <= 4 || roi.height() <= 4) { full.recycle(); return }
        val crop = Bitmap.createBitmap(full, roi.left, roi.top, roi.width(), roi.height())
        full.recycle()

        val processed = ImageUtil.applyPreprocess(crop, prefs.contrast, prefs.brightness, prefs.invert)
        crop.recycle()

        // Always push processed crop preview + raw text to UI
        OverlayBus.preview(processed)

        OcrEngine.recognizeCoord(processed) { result ->
            // processed bitmap already recycled via OverlayBus.preview path
            PreviewCache.lastText = result.rawText.take(120)
            OverlayBus.debugText(result.rawText.take(80))
            if (result.coord != null) {
                JoystickController.onPositionUpdate(result.coord.first, result.coord.second)
            }
        }
    }

    private fun effectiveRect(w: Int, h: Int): Rect {
        val saved = prefs.coordROI ?: return defaultRect(w, h)
        // ROI was set in screen pixels at calibration time; calibrate-view is full-screen so
        // capture buffer and screen pixels already align 1:1.
        return Rect(
            saved.left.coerceIn(0, w - 1),
            saved.top.coerceIn(0, h - 1),
            saved.right.coerceIn(1, w),
            saved.bottom.coerceIn(1, h)
        )
    }

    private fun defaultRect(w: Int, h: Int): Rect {
        val l = (w * 0.80f).toInt()
        val t = 0
        val r = w
        val b = (h * 0.27f).toInt()
        return Rect(l, t, r, b)
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
                nm.createNotificationChannel(NotificationChannel("autojoy", "AutoJoystick", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

object PreviewCache { var lastText: String = "" }
