package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
    @Volatile private var ocrInFlight = false

    private companion object {
        const val OCR_INTERVAL_MS = 280L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
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
            handler?.postDelayed(this, OCR_INTERVAL_MS)
        }
    }

    private fun grabAndOcr() {
        if (ocrInFlight) return
        val r = reader ?: return
        val img = r.acquireLatestImage() ?: return
        var processed: Bitmap? = null
        try {
            ocrInFlight = true
            val w = img.width; val h = img.height
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)

            // Use user-calibrated ROI rect when available, scaled from capture space to frame space.
            val roi = effectiveRect(w, h)
            if (roi.width() <= 4 || roi.height() <= 4) {
                bmp.recycle()
                ocrInFlight = false
                return
            }
            val full = Bitmap.createBitmap(bmp, 0, 0, w, h)
            bmp.recycle()
            val crop = Bitmap.createBitmap(full, roi.left, roi.top, roi.width(), roi.height())
            full.recycle()

            processed = ImageUtil.applyPreprocess(crop, prefs.contrast, prefs.brightness, prefs.invert)
            crop.recycle()

            // The preview needs its own bitmap because ML Kit owns the OCR input
            // until its asynchronous callback completes.
            val ocrBitmap = processed
            OverlayBus.preview(ocrBitmap.copy(Bitmap.Config.ARGB_8888, false))
            OcrEngine.recognizeCoord(ocrBitmap) { result ->
                PreviewCache.lastText = result.rawText.take(120)
                OverlayBus.debugText(result.rawText.take(80))
                if (result.coord != null) {
                    JoystickController.onPositionUpdate(result.coord.first, result.coord.second)
                }
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
                processed = null
                ocrInFlight = false
            }
        } catch (t: Throwable) {
            Log.w("AJ", "ocr frame: ${t.message}")
            processed?.let { if (!it.isRecycled) it.recycle() }
            ocrInFlight = false
        } finally {
            img.close()
        }
    }

    private fun effectiveRect(w: Int, h: Int): Rect {
        // ROI is stored per-orientation as screen fractions — convert to current frame pixels.
        val f = prefs.coordRoi(resources.configuration.orientation) ?: return defaultRect(w, h)
        return Rect(
            (f.left * w).toInt().coerceIn(0, w - 1),
            (f.top * h).toInt().coerceIn(0, h - 1),
            (f.right * w).toInt().coerceIn(1, w),
            (f.bottom * h).toInt().coerceIn(1, h)
        )
    }

    private fun defaultRect(w: Int, h: Int): Rect {
        val l = (w * 0.80f).toInt()
        val t = 0
        val r = w
        val b = (h * 0.27f).toInt()
        return Rect(l, t, r, b)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildDisplay()
    }

    /** Recreate ImageReader + VirtualDisplay at the new screen size after rotation,
     *  so captured frames are not stretched/letterboxed and the ROI fractions stay accurate. */
    private fun rebuildDisplay() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
            screenW = dm.widthPixels; screenH = dm.heightPixels
            try { vdisp?.release() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            vdisp = projection?.createVirtualDisplay(
                "ajcap", screenW, screenH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, null
            )
            Log.i("AJ", "capture rebuilt ${screenW}x${screenH}")
        } catch (t: Throwable) { Log.e("AJ", "rebuild display", t) }
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
