package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var rootView: View? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(1, Notification.Builder(this, "autojoy").setContentTitle("AutoJoystick running").build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        AccessibilityJoystickService.appContext = applicationContext
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() { wm.removeView(rootView); rootView = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun showOverlay() {
        val inflater = LayoutInflater.from(this)
        val v = inflater.inflate(R.layout.overlay, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40; lp.y = 80
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var px = 0; var py = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; px = event.rawX.toInt(); py = event.rawY.toInt() }
                    MotionEvent.ACTION_MOVE -> { lp.x = sx + (event.rawX - px).toInt(); lp.y = sy + (event.rawY - py).toInt(); wm.updateViewLayout(v, lp) }
                }
                return true
            }
        })
        v.findViewById<TextView>(R.id.ov_coord).text = "coord: —"
        wm.addView(v, lp)
        rootView = v
        OverlayBus.coordView = v.findViewById(R.id.ov_coord)
    }
}

object OverlayBus {
    var coordView: TextView? = null
    fun push(c: String) {
        coordView?.text = "coord: $c"
    }
}
