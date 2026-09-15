package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var rootView: View? = null
    private var collapsed = false
    private var calibrateView: View? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(1, Notification.Builder(this, "autojoy")
            .setContentTitle("AutoJoystick running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { rootView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { calibrateView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        rootView = null; calibrateView = null
        JoystickController.running = false
        super.onDestroy()
    }

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
        val v = LayoutInflater.from(this).inflate(R.layout.overlay, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40; lp.y = 80

        val panel = v.findViewById<View>(R.id.ov_panel)
        val dot = v.findViewById<View>(R.id.ov_dot)
        val title = v.findViewById<TextView>(R.id.ov_title)
        val minimize = v.findViewById<TextView>(R.id.ov_minimize)
        val coordView = v.findViewById<TextView>(R.id.ov_coord)
        val targetInput = v.findViewById<EditText>(R.id.ov_target)
        val btnStart = v.findViewById<Button>(R.id.ov_start)
        val btnStop = v.findViewById<Button>(R.id.ov_stop)
        val btnCal = v.findViewById<Button>(R.id.ov_calibrate)
        val status = v.findViewById<TextView>(R.id.ov_status)

        targetInput.setText(JoystickController.targetCoord)

        title.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var px = 0f; var py = 0f
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; px = e.rawX; py = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - px).toInt()
                        lp.y = sy + (e.rawY - py).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                }
                return true
            }
        })

        fun setCollapsed(c: Boolean) {
            collapsed = c
            panel.visibility = if (c) View.GONE else View.VISIBLE
            dot.visibility = if (c) View.VISIBLE else View.GONE
            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
        minimize.setOnClickListener { setCollapsed(true) }
        dot.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var px = 0f; var py = 0f; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; px = e.rawX; py = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val nx = sx + (e.rawX - px).toInt(); val ny = sy + (e.rawY - py).toInt()
                        if (Math.abs(nx - sx) > 12 || Math.abs(ny - sy) > 12) moved = true
                        lp.x = nx; lp.y = ny
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) setCollapsed(false) }
                }
                return true
            }
        })

        btnCal.setOnClickListener {
            status.text = "tap the joystick CENTER on screen…"
            setCollapsed(true)
            showCalibrationLayer(status)
        }

        btnStart.setOnClickListener {
            val t = targetInput.text.toString().trim().ifBlank { "51,35" }
            val parsed = JoystickController.parseCoord(t)
            if (parsed == null) {
                Toast.makeText(this, "Invalid coord: $t", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (JoystickController.joystickBaseX <= 0f) {
                status.text = "set joystick center first (CAL button)"
                return@setOnClickListener
            }
            if (AccessibilityJoystickService.instance == null) {
                status.text = "enable accessibility first"
                return@setOnClickListener
            }
            JoystickController.targetCoord = t
            JoystickController.running = true
            JoystickController.tick(status)
            status.text = "running → $t"
        }
        btnStop.setOnClickListener {
            JoystickController.running = false
            status.text = "stopped"
        }

        wm.addView(v, lp)
        rootView = v
        OverlayBus.coordView = coordView
        OverlayBus.statusView = status
    }

    /** Full-screen transparent tap layer: one tap = joystick center saved. */
    private fun showCalibrationLayer(status: TextView) {
        if (calibrateView != null) return
        val tv = TextView(this).apply {
            text = "TAP joystick center"
            setBackgroundColor(0x3300FF00)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val clp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        clp.gravity = Gravity.TOP or Gravity.START
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                JoystickController.joystickBaseX = e.rawX
                JoystickController.joystickBaseY = e.rawY
                Prefs.saveJoystick(this, e.rawX, e.rawY)
                status.text = "joystick center = ${e.rawX.toInt()},${e.rawY.toInt()}"
                try { wm.removeView(tv) } catch (_: Throwable) {}
                calibrateView = null
                // expand panel back
                rootView?.let {
                    it.findViewById<View>(R.id.ov_panel).visibility = View.VISIBLE
                    it.findViewById<View>(R.id.ov_dot).visibility = View.GONE
                }
            }
            true
        }
        wm.addView(tv, clp)
        calibrateView = tv
    }
}

object OverlayBus {
    var coordView: TextView? = null
    var statusView: TextView? = null
    fun push(c: String) { coordView?.post { coordView?.text = "coord: $c" } }
    fun status(s: String) { statusView?.post { statusView?.text = s } }
}
